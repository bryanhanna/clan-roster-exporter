package clanrosterexporter;

import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.clan.ClanID;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@PluginDescriptor(
        name = "Clan Roster Exporter",
        description = "POSTs clan roster (names, ranks, join dates) to a configurable URL",
        tags = {"clan", "export", "http", "webhook"}
)
public class ClanRosterExporterPlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(ClanRosterExporterPlugin.class);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private ClientToolbar clientToolbar;
    @Inject
    private ClanRosterExporterConfig config;
    @Inject
    private ScheduledExecutorService scheduler;

    private ClanRosterExporterPanel panel;
    private NavigationButton navButton;
    private ScheduledFuture<?> intervalTask;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Provides
    ClanRosterExporterConfig provideConfig(ConfigManager cm)
    {
        return cm.getConfig(ClanRosterExporterConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new ClanRosterExporterPanel(this::requestExportManual);

        BufferedImage icon = null;
        try {
            icon = ImageUtil.loadImageResource(ClanRosterExporterPlugin.class, "icon.png");
        } catch (Exception ignored) {
        }
        if (icon == null) {
            icon = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }

        navButton = NavigationButton.builder()
                .tooltip("Clan Roster Exporter")
                .icon(icon)
                .priority(6)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
        panel.setStatus("Set Export URL in plugin settings. Exports run on login, clan updates, interval, or Export now.");

        rescheduleInterval();
        clientThread.invokeLater(this::requestExportIfConfigured);

        LOG.info("[ClanRosterExporter] started");
    }

    @Override
    protected void shutDown()
    {
        cancelInterval();
        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        panel = null;
        LOG.info("[ClanRosterExporter] stopped");
    }

    private void cancelInterval()
    {
        if (intervalTask != null) {
            intervalTask.cancel(false);
            intervalTask = null;
        }
    }

    private void rescheduleInterval()
    {
        cancelInterval();
        int mins = config.intervalMinutes();
        if (mins <= 0) {
            return;
        }
        intervalTask = scheduler.scheduleWithFixedDelay(
                () -> clientThread.invokeLater(this::requestExportIfConfigured),
                mins,
                mins,
                TimeUnit.MINUTES);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() == GameState.LOGGED_IN) {
            clientThread.invokeLater(this::requestExportIfConfigured);
        }
    }

    @Subscribe
    public void onClanChannelChanged(ClanChannelChanged e)
    {
        clientThread.invokeLater(this::requestExportIfConfigured);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"clanrosterexporter".equals(e.getGroup())) {
            return;
        }
        rescheduleInterval();
        clientThread.invokeLater(this::requestExportIfConfigured);
    }

    private void requestExportManual()
    {
        clientThread.invoke(() -> collectAndPost(true));
    }

    private void requestExportIfConfigured()
    {
        collectAndPost(false);
    }

    /**
     * Must run on client thread.
     */
    private void collectAndPost(boolean manual)
    {
        String url = config.exportUrl();
        if (url == null || url.isBlank()) {
            if (manual) {
                panel.setStatus("Export URL is empty. Set it in plugin configuration.");
            }
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN) {
            if (manual) {
                panel.setStatus("Log in to export roster.");
            }
            return;
        }

        ClanSettings cs = getClan();
        if (cs == null || cs.getMembers() == null) {
            if (manual) {
                panel.setStatus("No clan settings available yet.");
            }
            return;
        }

        String clanName = resolveClanName(cs);
        String json = buildJson(cs, clanName);
        final int memberCount = cs.getMembers().size();
        if (manual) {
            panel.setBusy(true);
        }

        final String trimmedUrl = url.trim();
        final String token = config.bearerToken();
        final boolean manualExport = manual;
        final ClanRosterExporterPanel ui = panel;

        scheduler.execute(() -> {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(trimmedUrl))
                        .timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));

                if (token != null && !token.isBlank()) {
                    rb.header("Authorization", "Bearer " + token.trim());
                }

                HttpResponse<String> response = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                String base = "Last export: HTTP " + code + " at " + Instant.now() + " (" + memberCount + " members)";
                final String msg = (code < 200 || code >= 300)
                        ? base + ". Body: " + truncate(response.body(), 200)
                        : base;
                LOG.info("[ClanRosterExporter] {}", msg);
                SwingUtilities.invokeLater(() -> {
                    ui.setStatus(msg);
                    if (manualExport) {
                        ui.setBusy(false);
                    }
                });
            } catch (Exception ex) {
                LOG.warn("[ClanRosterExporter] export failed", ex);
                final String err = "Export failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    ui.setStatus(err);
                    if (manualExport) {
                        ui.setBusy(false);
                    }
                });
            }
        });
    }

    private static String truncate(String s, int max)
    {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private ClanSettings getClan()
    {
        try {
            ClanSettings cs = client.getClanSettings(ClanID.CLAN);
            if (cs != null && cs.getMembers() != null) {
                return cs;
            }
        } catch (Throwable ignored) {
        }

        try {
            ClanSettings cs = client.getClanSettings();
            if (cs != null && cs.getMembers() != null) {
                return cs;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static String resolveClanName(ClanSettings cs)
    {
        try {
            String n = cs.getName();
            if (n != null && !n.isBlank()) {
                return n;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String buildJson(ClanSettings cs, String clanName)
    {
        String exportedAt = Instant.now().toString();
        List<String> items = new ArrayList<>();

        for (ClanMember member : cs.getMembers()) {
            String name = member.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            ClanRank rank = member.getRank();
            int rankId = safeRankId(rank);
            ClanTitle title = rank != null ? cs.titleForRank(rank) : null;
            String rankTitle = title != null && title.getName() != null ? title.getName() : "Not ranked";

            LocalDate joined = null;
            try {
                joined = member.getJoinDate();
            } catch (Throwable ignored) {
            }

            items.add(String.format(
                    "{\"name\":%s,\"rankTitle\":%s,\"rank\":%d,\"joinDate\":%s}",
                    jsonString(name),
                    jsonString(rankTitle),
                    rankId,
                    joined == null ? "null" : jsonString(joined.format(ISO_DATE))));
        }

        return String.format(
                "{\"exportedAt\":%s,\"clanName\":%s,\"memberCount\":%d,\"members\":[%s]}",
                jsonString(exportedAt),
                jsonString(clanName),
                items.size(),
                String.join(",", items));
    }

    private static int safeRankId(ClanRank rank)
    {
        if (rank == null) {
            return -1;
        }
        try {
            return rank.getRank();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String jsonString(String s)
    {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
