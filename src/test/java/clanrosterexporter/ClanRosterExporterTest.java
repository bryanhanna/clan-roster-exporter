package clanrosterexporter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ClanRosterExporterTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(ClanRosterExporterPlugin.class);
        RuneLite.main(args);
    }
}
