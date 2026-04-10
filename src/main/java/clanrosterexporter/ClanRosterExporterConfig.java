package clanrosterexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("clanrosterexporter")
public interface ClanRosterExporterConfig extends Config
{
    @ConfigItem(
            keyName = "exportUrl",
            name = "Export URL",
            description = "HTTPS URL to POST JSON roster data to. Leave empty to disable automatic exports."
    )
    default String exportUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "bearerToken",
            name = "Bearer token",
            description = "Optional. If set, sends header Authorization: Bearer with this value on each request."
    )
    default String bearerToken()
    {
        return "";
    }

    @ConfigItem(
            keyName = "intervalMinutes",
            name = "Export interval (minutes)",
            description = "How often to export while logged in. 0 = only on login, clan updates, and manual Export."
    )
    @Range(min = 0, max = 1440)
    default int intervalMinutes()
    {
        return 30;
    }
}
