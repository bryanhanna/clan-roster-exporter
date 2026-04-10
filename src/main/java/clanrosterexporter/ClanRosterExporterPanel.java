package clanrosterexporter;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class ClanRosterExporterPanel extends PluginPanel
{
    private final JLabel statusLabel = new JLabel();
    private final JButton exportButton = new JButton("Export now");

    public ClanRosterExporterPanel(Runnable onExportNow)
    {
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel header = new JLabel("Clan Roster Exporter");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setForeground(Color.WHITE);

        statusLabel.setForeground(Color.LIGHT_GRAY);
        statusLabel.setVerticalAlignment(SwingConstants.TOP);
        statusLabel.setBorder(new EmptyBorder(4, 0, 0, 0));

        exportButton.setFocusable(false);
        exportButton.addActionListener(e -> {
            if (onExportNow != null) {
                onExportNow.run();
            }
        });

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(header, BorderLayout.WEST);
        north.add(exportButton, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);
        add(statusLabel, BorderLayout.CENTER);
    }

    public void setBusy(boolean busy)
    {
        SwingUtilities.invokeLater(() -> exportButton.setEnabled(!busy));
    }

    public void setStatus(String text)
    {
        SwingUtilities.invokeLater(() ->
                statusLabel.setText("<html><body style='width: 200px'>" + (text == null ? "" : text) + "</body></html>"));
    }
}
