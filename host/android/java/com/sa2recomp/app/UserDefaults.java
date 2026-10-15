package com.sa2recomp.app;

/** The Config/ files SA2's Launcher.exe writes on its first run, byte for byte the installer's
 * defaults (host/install/sa2_config.cpp SA2_USER_DEFAULTS). OpenSA2 writes each one the game
 * folder and the overlay both lack into the overlay, as the installer does. */
final class UserDefaults {
    static final String[][] FILES = {
        {"Config/UserConfig.cfg",
         "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
         + "<Configs FullScreen=\"0\" Display=\"0\" Res=\"0\" Width=\"1280\" Height=\"720\" RefreshRate=\"60\" "
         + "Language=\"0\" Analytics=\"0\" />\n"},
        {"Config/Keyboard.cfg",
         "<?xml version=\"1.0\" ?>\n"
         + "<Setup Layout=\"0\" />\n"
         + "<Players>\n"
         + "    <Player InputWay=\"0\" Vibrate=\"0\">\n"
         + "        <key index=\"22\" />\n"
         + "        <key index=\"18\" />\n"
         + "        <key index=\"0\" />\n"
         + "        <key index=\"3\" />\n"
         + "        <key index=\"8\" />\n"
         + "        <key index=\"9\" />\n"
         + "        <key index=\"20\" />\n"
         + "        <key index=\"10\" />\n"
         + "        <key index=\"25\" />\n"
         + "        <key index=\"16\" />\n"
         + "        <key index=\"37\" />\n"
         + "        <key index=\"23\" />\n"
         + "        <key index=\"4\" />\n"
         + "        <key index=\"37\" />\n"
         + "        <key index=\"27\" />\n"
         + "        <key index=\"67\" />\n"
         + "    </Player>\n"
         + "    <Player InputWay=\"0\" Vibrate=\"0\">\n"
         + "        <key index=\"83\" />\n"
         + "        <key index=\"65\" />\n"
         + "        <key index=\"73\" />\n"
         + "        <key index=\"79\" />\n"
         + "        <key index=\"102\" />\n"
         + "        <key index=\"98\" />\n"
         + "        <key index=\"101\" />\n"
         + "        <key index=\"99\" />\n"
         + "        <key index=\"66\" />\n"
         + "        <key index=\"104\" />\n"
         + "        <key index=\"37\" />\n"
         + "        <key index=\"76\" />\n"
         + "        <key index=\"105\" />\n"
         + "        <key index=\"37\" />\n"
         + "        <key index=\"28\" />\n"
         + "        <key index=\"111\" />\n"
         + "    </Player>\n"
         + "</Players>\n"},
    };

    private UserDefaults() {}
}
