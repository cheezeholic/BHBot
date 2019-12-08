import com.google.common.collect.Maps;
import net.pushover.client.MessagePriority;
import net.pushover.client.PushoverException;
import net.pushover.client.PushoverMessage;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Point;
import org.openqa.selenium.remote.UnreachableBrowserException;

import javax.activation.MimetypesFileTypeMap;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Comparator.comparing;

public class DungeonThread implements Runnable {
    static final int SECOND = 1000;
    static final int MINUTE = 60 * SECOND;
    private static final int HOUR = 60 * MINUTE;
    private static final int DAY = 24 * HOUR;

    private static int globalShards;
    private static int globalBadges;
    private static int globalEnergy;
    private static int globalTickets;
    private static int globalTokens;

    private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");

    /*
          Match the character “z” literally (case sensitive) «z»
          Match the regex below and capture its match into a backreference named “zone” (also backreference number 1) «(?<zone>\d{1,2})»
             Match a single character that is a “digit” (ASCII 0–9 only) «\d{1,2}»
                Between one and 2 times, as many times as possible, giving back as needed (greedy) «{1,2}»
          Match the character “d” literally (case sensitive) «d»
          Match the regex below and capture its match into a backreference named “dungeon” (also backreference number 2) «(?<dungeon>[1234])»
             Match a single character from the list “1234” «[1234]»
          Match a single character that is a “whitespace character” (ASCII space, tab, line feed, carriage return, vertical tab, form feed) «\s+»
             Between one and unlimited times, as many times as possible, giving back as needed (greedy) «+»
          Match the regex below and capture its match into a backreference named “difficulty” (also backreference number 3) «(?<difficulty>[123])»
             Match a single character from the list “123” «[123]»
         */
    private Pattern dungeonRegex = Pattern.compile("z(?<zone>\\d{1,2})d(?<dungeon>[1234])\\s+(?<difficulty>[123])");
    @SuppressWarnings("FieldCanBeLocal")
    private final int MAX_NUM_FAILED_RESTARTS = 5;
    @SuppressWarnings("FieldCanBeLocal")
    private final boolean QUIT_AFTER_MAX_FAILED_RESTARTS = false;
    @SuppressWarnings("FieldCanBeLocal")
    private final long MAX_IDLE_TIME = 15 * MINUTE;
    @SuppressWarnings("FieldCanBeLocal")
    private final int MAX_CONSECUTIVE_EXCEPTIONS = 10;

    private boolean[] revived = {false, false, false, false, false};
    private int potionsUsed = 0;
    private boolean startTimeCheck = false;
    private boolean speedChecked = false;
    private boolean oneTimeshrineCheck = false;
    private boolean autoShrined = false;
    private long activityStartTime;
    private boolean encounterStatus = true;
    private long outOfEncounterTimestamp = 0;
    private long inEncounterTimestamp = 0;
    private boolean specialDungeon; //d4 check for closing properly when no energy
    private String expeditionFailsafePortal = "";
    private int expeditionFailsafeDifficulty = 0;

    // Generic counters HashMap
    HashMap<State, DungeonCounter> counters = new HashMap<>();

    private int numFailedRestarts = 0; // in a row
    // When we do not have anymore gems to use this is true
    private boolean noGemsToBribe = false;
    private State state; // at which stage of the game/menu are we currently?

    private long ENERGY_CHECK_INTERVAL = 10 * MINUTE;
    private long TICKETS_CHECK_INTERVAL = 10 * MINUTE;
    private long TOKENS_CHECK_INTERVAL = 10 * MINUTE;
    private long BADGES_CHECK_INTERVAL = 10 * MINUTE;
    @SuppressWarnings("FieldCanBeLocal")
    private long BONUS_CHECK_INTERVAL = 10 * MINUTE;

    private long timeLastEnergyCheck = 0; // when did we check for Energy the last time?
    private long timeLastShardsCheck = 0; // when did we check for Shards the last time?
    private long timeLastTicketsCheck = 0; // when did we check for Tickets the last time?
    private long timeLastTrialsTokensCheck = 0; // when did we check for trials Tokens the last time?
    private long timeLastGauntletTokensCheck = 0; // when did we check for gauntlet Tokens the last time?
    private long timeLastExpBadgesCheck = 0; // when did we check for badges the last time?
    private long timeLastInvBadgesCheck = 0; // when did we check for badges the last time?
    private long timeLastGVGBadgesCheck = 0; // when did we check for badges the last time?
    private long timeLastBountyCheck = 0; // when did we check for bounties the last time?
    private long timeLastBonusCheck = 0; // when did we check for bonuses (active consumables) the last time?
    private long timeLastFishingBaitsCheck = 0; // when did we check for fishing baits the last time?
    private long timeLastFishingCheck = 0; // when did we check for fishing last time?
    private long timeLastPOAlive = 0; // when did we check for fishing last time?
    private final long botStartTime = Misc.getTime();
    /**
     * Number of consecutive exceptions. We need to track it in order to detect crash loops that we must break by restarting the Chrome driver. Or else it could get into loop and stale.
     */
    private int numConsecutiveException = 0;

    /**
     * autoshrine settings save
     */
    private boolean ignoreBossSetting = false;
    private boolean ignoreShrinesSetting = false;
    /**
     * global autorune vals
     */
    private boolean autoBossRuned = false;
    private boolean oneTimeRuneCheck = false;
    private MinorRune leftMinorRune;
    private MinorRune rightMinorRune;
    private Iterator<String> activitysIterator = BHBot.settings.activitiesEnabled.iterator();

    static void printFamiliars() {

        List<String> folders = new ArrayList<>();
        folders.add("cues/familiars/old_format");
        folders.add("cues/familiars/new_format");

        Set<String> uniqueFamiliars = new TreeSet<>();

        for (String cuesPath : folders) {
            // We make sure that the last char of the path is a folder separator
            if (!"/".equals(cuesPath.substring(cuesPath.length() - 1))) cuesPath += "/";

            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            URL url = classLoader.getResource(cuesPath);
            if (url != null) { // Run from the IDE
                if ("file".equals(url.getProtocol())) {

                    InputStream in = classLoader.getResourceAsStream(cuesPath);
                    if (in == null) {
                        BHBot.logger.error("Impossible to create InputStream in printFamiliars");
                        return;
                    }

                    BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    String resource;

                    while (true) {
                        try {
                            resource = br.readLine();
                            if (resource == null) break;
                        } catch (IOException e) {
                            BHBot.logger.error("Error while reading resources in printFamiliars", e);
                            continue;
                        }
                        int dotPosition = resource.lastIndexOf('.');
                        String fileExtension = dotPosition > 0 ? resource.substring(dotPosition + 1) : "";
                        if ("png".equals(fileExtension.toLowerCase())) {
                            String cueName = resource.substring(0, dotPosition);

                            cueName = cueName.replace("cue", "");

                            uniqueFamiliars.add(cueName.toLowerCase());
                        }
                    }
                } else if ("jar".equals(url.getProtocol())) { // Run from JAR
                    String path = url.getPath();
                    String jarPath = path.substring(5, path.indexOf("!"));

                    String decodedURL;
                    try {
                        decodedURL = URLDecoder.decode(jarPath, StandardCharsets.UTF_8.name());
                    } catch (UnsupportedEncodingException e) {
                        BHBot.logger.error("Impossible to decode path for jar in printFamiliars: " + jarPath, e);
                        return;
                    }

                    JarFile jar;
                    try {
                        jar = new JarFile(decodedURL);
                    } catch (IOException e) {
                        BHBot.logger.error("Impossible to open JAR file in printFamiliars: " + decodedURL, e);
                        return;
                    }

                    Enumeration<JarEntry> entries = jar.entries();

                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(cuesPath) && !cuesPath.equals(name)) {
                            URL resource = classLoader.getResource(name);

                            if (resource == null) continue;

                            String resourcePath = resource.toString();
                            BHBot.logger.trace("resourcePath: " + resourcePath);
                            if (!resourcePath.contains("!")) {
                                BHBot.logger.warn("Unexpected resource filename in load printFamiliars");
                                continue;
                            }

                            String[] fileDetails = resourcePath.split("!");
                            String resourceRelativePath = fileDetails[1];
                            BHBot.logger.trace("resourceRelativePath : " + resourceRelativePath);
                            int lastSlashPosition = resourceRelativePath.lastIndexOf('/');
                            String fileName = resourceRelativePath.substring(lastSlashPosition + 1);

                            int dotPosition = fileName.lastIndexOf('.');
                            String fileExtension = dotPosition > 0 ? fileName.substring(dotPosition + 1) : "";
                            if ("png".equals(fileExtension.toLowerCase())) {
                                String cueName = fileName.substring(0, dotPosition);

                                cueName = cueName.replace("cue", "");
                                BHBot.logger.trace("cueName: " + cueName);

                                // resourceRelativePath begins with a '/' char and we want to be sure to remove it
                                uniqueFamiliars.add(cueName.toLowerCase());
                            }
                        }
                    }

                }
            }
        }

        StringBuilder familiarString = new StringBuilder();
        int currentFamiliar = 1;

        for (String familiar : uniqueFamiliars) {
            if (familiarString.length() > 0) familiarString.append(", ");
            if (currentFamiliar % 5 == 0) familiarString.append("\n");
            familiarString.append(familiar);
            currentFamiliar++;
        }

        BHBot.logger.info(familiarString.toString());
    }

    private void dumpCrashLog() {
        // save screen shot:
        String file = saveGameScreen("crash", "errors");

        if (file == null) {
            BHBot.logger.error("Impossible to create crash screenshot");
            return;
        }

        // save stack trace:
        boolean savedST = Misc.saveTextFile(file.substring(0, file.length() - 4) + ".txt", Misc.getStackTrace());
        if (!savedST) {
            BHBot.logger.info("Impossible to save the stack trace in dumpCrashLog!");
        }

        if (BHBot.settings.enablePushover && BHBot.settings.poNotifyCrash) {
            File poCrashScreen = new File(file);
            sendPushOverMessage("BHBot CRASH!",
                    "BHBot has crashed and a driver emergency restart has been performed!\n\n" + Misc.getStackTrace(), "falling",
                    MessagePriority.HIGH, poCrashScreen.exists() ? poCrashScreen : null);
        }
    }

    private void restart() {
        restart(true); // assume emergency restart
    }

    void restart(boolean emergency) {
        restart(emergency, false); // assume emergency restart
    }

    /**
     * @param emergency true in case something bad happened (some kind of an error for which we had to do a restart)
     */
    void restart(boolean emergency, boolean useDoNotShareLink) {
        oneTimeshrineCheck = false; //reset first run shrine check in case its enabled after restarting

        // take emergency screenshot (which will have the developer to debug the problem):
        if (emergency) {
            BHBot.logger.warn("Doing driver emergency restart...");
            dumpCrashLog();
        }

        try {
            BHBot.browser.restart(useDoNotShareLink);
        } catch (Exception e) {

            if (e instanceof NoSuchElementException)
                BHBot.logger.warn("Problem: web element with id 'game' not found!");
            if (e instanceof MalformedURLException)
                BHBot.logger.warn("Problem: malformed url detected!");
            if (e instanceof UnreachableBrowserException) {
                BHBot.logger.error("Impossible to connect to the BHBot.browser. Make sure chromedirver is started. Will retry in a few minutes... (sleeping)");
                Misc.sleep(5 * MINUTE);
                restart();
                return;
            }

            numFailedRestarts++;
            if (QUIT_AFTER_MAX_FAILED_RESTARTS && numFailedRestarts > MAX_NUM_FAILED_RESTARTS) {
                BHBot.logger.fatal("Something went wrong with driver restart. Number of restarts exceeded " + MAX_NUM_FAILED_RESTARTS + ", this is why I'm aborting...");
                BHBot.finished = true;
            } else {
                BHBot.logger.error("Something went wrong with driver restart. Will retry in a few minutes... (sleeping)", e);
                Misc.sleep(5 * MINUTE);
                restart();
            }
            return;
        }

        BHBot.browser.detectSignInFormAndHandleIt(); // just in case (happens seldom though)
        
        BHBot.browser.scrollGameIntoView();

        int counter = 0;
        boolean restart = false;
        while (true) {
            try {
                BHBot.browser.readScreen();

                MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("Login"), BHBot.browser);
                BHBot.browser.detectLoginFormAndHandleIt(seg);
            } catch (Exception e) {
                counter++;
                if (counter > 20) {
                    BHBot.logger.error("Error: <" + e.getMessage() + "> while trying to detect and handle login form. Restarting...", e);
                    restart = true;
                    break;
                }

                Misc.sleep(10 * SECOND);
                continue;
            }
            break;
        }
        if (restart) {
            restart();
            return;
        }

        BHBot.logger.info("Game element found. Starting to run bot..");

        if (BHBot.settings.idleMode) { //skip startup checks if we are in idle mode
            oneTimeshrineCheck = true;
            oneTimeRuneCheck = true;
        }

        if ((BHBot.settings.activitiesEnabled.contains("d")) && (BHBot.settings.activitiesEnabled.contains("w"))) {
            BHBot.logger.info("Both Dungeons and World Boss selected, disabling World Boss.");
            BHBot.logger.info("To run a mixture of both use a low lobby timer and enable dungeonOnTimeout");
            BHBot.settings.activitiesEnabled.remove("w");
        }
        state = State.Loading;
        BHBot.scheduler.resetIdleTime();
        BHBot.scheduler.resume(); // in case it was paused
        numFailedRestarts = 0; // must be last line in this method!
    }

    public void run() {
        BHBot.logger.info("Bot started successfully!");

        restart(false);

        // We initialize the counter HasMap using the state as key
        for (State state : State.values()) {
            counters.put(state, new DungeonCounter(0, 0));
        }

        while (!BHBot.finished) {
            BHBot.scheduler.backupIdleTime();
            try {
                BHBot.scheduler.process();
                if (BHBot.scheduler.isPaused()) continue;

                if (Misc.getTime() - BHBot.scheduler.getIdleTime() > MAX_IDLE_TIME) {
                    BHBot.logger.warn("Idle time exceeded... perhaps caught in a loop? Restarting... (state=" + state + ")");
                    saveGameScreen("idle-timeout-error", "errors");

                    // Safety measure to avoid being stuck forever in dungeons
                    if (state != State.Main && state != State.Loading) {
                        BHBot.logger.info("Ensuring that autoShrine settings are disabled");
                        if (!checkShrineSettings(false, false)) {
                            BHBot.logger.error("It was not possible to verify autoShrine settings");
                        }
                        autoShrined = false;

                        if (!BHBot.settings.autoRuneDefault.isEmpty()) {
                            BHBot.logger.info("Re-validating autoRunes");
                            if (!detectEquippedMinorRunes(true, true)) {
                                BHBot.logger.error("It was not possible to verify the equipped runes!");
                            }
                        }
                    }

                    if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                        String idleTimerScreenName = saveGameScreen("idle-timer", BHBot.browser.getImg());
                        File idleTimerScreenFile = idleTimerScreenName != null ? new File(idleTimerScreenName) : null;
                        if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                            sendPushOverMessage("Idle timer exceeded", "Idle time exceeded while state = " + state, "siren", MessagePriority.NORMAL, idleTimerScreenFile);

                            if (idleTimerScreenFile != null && !idleTimerScreenFile.delete()) {
                                BHBot.logger.error("Impossible to delete idle timer screenshot.");
                            }
                        }
                    }

                    restart();
                    continue;
                }
                BHBot.scheduler.resetIdleTime();

                BHBot.browser.moveMouseAway(); // just in case. Sometimes we weren't able to claim daily reward because mouse was in center and popup window obfuscated the claim button (see screenshot of that error!)
                MarvinSegment seg;
                BHBot.browser.readScreen();

                seg = MarvinSegment.fromCue(BrowserManager.cues.get("UnableToConnect"), BHBot.browser);
                if (seg != null) {
                    BHBot.logger.info("'Unable to connect' dialog detected. Reconnecting...");
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Reconnect"), 5 * SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);
                    Misc.sleep(5 * SECOND);
                    state = State.Loading;
                    continue;
                }


                // check for "Bit Heroes is currently down for maintenance. Please check back shortly!" window:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Maintenance"), BHBot.browser);
                if (seg != null) {
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Reconnect"), 5 * SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);
                    BHBot.logger.info("Maintenance dialog dismissed.");
                    Misc.sleep(5 * SECOND);
                    state = State.Loading;
                    continue;
                }

                // check for "You have been disconnected" dialog:
                MarvinSegment uhoh = MarvinSegment.fromCue(BrowserManager.cues.get("UhOh"), BHBot.browser);
                MarvinSegment dc = MarvinSegment.fromCue(BrowserManager.cues.get("Disconnected"), BHBot.browser);
                if (uhoh != null && dc != null) {
                    if (BHBot.scheduler.isUserInteracting || BHBot.scheduler.dismissReconnectOnNextIteration) {
                        BHBot.scheduler.isUserInteracting = false;
                        BHBot.scheduler.dismissReconnectOnNextIteration = false;
                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Reconnect"), 5 * SECOND, BHBot.browser);
                        BHBot.browser.clickOnSeg(seg);
                        BHBot.logger.info("Disconnected dialog dismissed (reconnecting).");
                        Misc.sleep(5 * SECOND);
                    } else {
                        BHBot.scheduler.isUserInteracting = true;
                        // probably user has logged in, that's why we got disconnected. Lets leave him alone for some time and then resume!
                        BHBot.logger.info("Disconnect has been detected. Probably due to user interaction. Sleeping for " + Misc.millisToHumanForm((long) BHBot.settings.reconnectTimer * MINUTE) + "...");
                        BHBot.scheduler.pause(BHBot.settings.reconnectTimer * MINUTE);
                    }
                    state = State.Loading;
                    continue;
                }

                BHBot.scheduler.dismissReconnectOnNextIteration = false; // must be done after checking for "Disconnected" dialog!

                // check for "There is a new update required to play" and click on "Reload" button:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Reload"), BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                    BHBot.logger.info("Update dialog dismissed.");
                    Misc.sleep(5 * SECOND);
                    state = State.Loading;
                    continue;
                }

                // close any PMs:
                handlePM();

                // check for "Are you still there?" popup:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("AreYouThere"), BHBot.browser);
                if (seg != null) {
                    BHBot.scheduler.restoreIdleTime();
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Yes"), 2 * SECOND, BHBot.browser);
                    if (seg != null)
                        BHBot.browser.clickOnSeg(seg);
                    else {
                        BHBot.logger.info("Problem: 'Are you still there?' popup detected, but 'Yes' button not detected. Ignoring...");
                        continue;
                    }
                    Misc.sleep(2 * SECOND);
                    continue; // skip other stuff, we must first get rid of this popup!
                }

                // check for "News" popup:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("News"), BHBot.browser);
                if (seg != null) {
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Close"), 2 * SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);
                    BHBot.logger.info("News popup dismissed.");
                    BHBot.browser.readScreen(2 * SECOND);
                    continue;
                }

                //Handle weekly rewards from events
                handleWeeklyRewards();

                // check for daily rewards popup:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("DailyRewards"), BHBot.browser);
                if (seg != null) {
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Claim"), 5 * SECOND, BHBot.browser);
                    if (seg != null) {
                        if ((BHBot.settings.screenshots.contains("d"))) {
                            BufferedImage reward = BHBot.browser.getImg().getSubimage(131, 136, 513, 283);
                            saveGameScreen("daily_reward", "rewards", reward);
                        }
                        BHBot.browser.clickOnSeg(seg);
                    } else {
                        BHBot.logger.error("Problem: 'Daily reward' popup detected, however could not detect the 'claim' button. Restarting...");
                        restart();
                        continue; // may happen every while, rarely though
                    }

                    BHBot.browser.readScreen(5 * SECOND);
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Items"), SECOND, BHBot.browser);
                    if (seg == null) {
                        // we must terminate this thread... something happened that should not (unexpected). We must restart the thread!
                        BHBot.logger.error("Error: there is no 'Items' dialog open upon clicking on the 'Claim' button. Restarting...");
                        restart();
                        continue;
                    }
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);
                    BHBot.logger.info("Daily reward claimed successfully.");
                    Misc.sleep(2 * SECOND);

                    //We check for news and close so we don't take a gem count every time the bot starts
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("News"), SECOND, BHBot.browser);
                    if (seg != null) {
                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Close"), 2 * SECOND, BHBot.browser);
                        BHBot.browser.clickOnSeg(seg);
                        BHBot.logger.info("News popup dismissed.");
                        BHBot.browser.readScreen(2 * SECOND);

                        if ("7".equals(new SimpleDateFormat("u").format(new Date()))) { //if it's Sunday
                            if ((BHBot.settings.screenshots.contains("wg"))) {
                                /* internal code for collecting number cues for the micro font
                                MarvinImage gems = new MarvinImage(img.getSubimage(133, 16, 80, 14));
                                makeImageBlackWhite(gems, new Color(25, 25, 25), new Color(255, 255, 255), 64);
                                BufferedImage gemsbw = gems.getBufferedImage();
                                int num = readNumFromImg(gemsbw, "micro", new HashSet<>());
                                */
                                BufferedImage gems = BHBot.browser.getImg().getSubimage(133, 16, 80, 14);
                                saveGameScreen("weekly-gems", "gems", gems);
                            }
                        } else {
                            if ((BHBot.settings.screenshots.contains("dg"))) {
                                /* internal code for collecting number cues for the micro font
                                MarvinImage gems = new MarvinImage(img.getSubimage(133, 16, 80, 14));
                                makeImageBlackWhite(gems, new Color(25, 25, 25), new Color(255, 255, 255), 64);
                                BufferedImage gemsbw = gems.getBufferedImage();
                                int num = readNumFromImg(gemsbw, "micro", new HashSet<>());
                                */
                                BufferedImage gems = BHBot.browser.getImg().getSubimage(133, 16, 80, 14);
                                saveGameScreen("daily-gems", "gems", gems); //else screenshot daily count
                            }
                        }

                        continue;
                    }

                    continue;
                }

                // check for "recently disconnected" popup:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("RecentlyDisconnected"), BHBot.browser);
                if (seg != null) {
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 2 * SECOND, BHBot.browser);
                    if (seg == null) {
                        BHBot.logger.error("Error: detected 'recently disconnected' popup but could not find 'Yes' button. Restarting...");
                        restart();
                        continue;
                    }

                    BHBot.browser.clickOnSeg(seg);
                    if (state == State.Main || state == State.Loading) {
                        // we set this when we are not sure of what type of dungeon we are doing
                        state = State.UnidentifiedDungeon;
                    } else {
                        BHBot.logger.debug("RecentlyDisconnected status is: " + state);
                    }
                    BHBot.logger.info("'You were recently in a dungeon' dialog detected and confirmed. Resuming dungeon...");
                    Misc.sleep(60 * SECOND); //long sleep as if the checkShrine didn't find the potion button we'd enter a restart loop
                    checkShrineSettings(false, false); //in case we are stuck in a dungeon lets enable shrines/boss
                    continue;
                }

                //Dungeon crash failsafe, this can happen if you crash and reconnect quickly, then get placed back in the dungeon with no reconnect dialogue
                if (state == State.Loading) {
                    MarvinSegment autoOn = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOn"), BHBot.browser);
                    MarvinSegment autoOff = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), BHBot.browser);
                    if (autoOn != null || autoOff != null) { //if we're in Loading state, with auto button visible, then we need to change state
                        state = State.UnidentifiedDungeon; // we are not sure what type of dungeon we are doing
                        BHBot.logger.warn("Possible dungeon crash, activating failsafe");
                        saveGameScreen("dungeon-crash-failsafe", "errors");
                        checkShrineSettings(false, false); //in case we are stuck in a dungeon lets enable shrines/boss
                        continue;
                    }
                }

                // process dungeons of any kind (if we are in any):
                if (state == State.Raid || state == State.Trials || state == State.Gauntlet || state == State.Dungeon || state == State.PVP || state == State.GVG || state == State.Invasion || state == State.UnidentifiedDungeon || state == State.Expedition || state == State.WorldBoss) {
                    processDungeon();
                    continue;
                }

                // check if we are in the main menu:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Main"), BHBot.browser);

                if (seg != null) {

                    /* The bot is now fully started, so based on the options we search the logs looking for the
                     * do_not_share url and if we find it, we save it for later usage
                     */
                    if (!BHBot.browser.isDoNotShareUrl() && BHBot.settings.useDoNotShareURL) {
                        restart(false, true);
                        continue;
                    }

                    state = State.Main;

                    // check for pushover alive notifications!
                    if (BHBot.settings.enablePushover && BHBot.settings.poNotifyAlive > 0) {

                        // startup notification
                        if (timeLastPOAlive == 0) {
                            timeLastPOAlive = Misc.getTime();

                            timeLastPOAlive = Misc.getTime();
                            String aliveScreenName = saveGameScreen("alive-screen");
                            File aliveScreenFile = aliveScreenName != null ? new File(aliveScreenName) : null;

                            sendPushOverMessage("Startup notification", "BHBot has been successfully started!", MessagePriority.QUIET, aliveScreenFile);
                            if (aliveScreenFile != null && !aliveScreenFile.delete())
                                BHBot.logger.warn("Impossible to delete tmp img for startup notification.");
                        }

                        // periodic notification
                        if ((Misc.getTime() - timeLastPOAlive) > (BHBot.settings.poNotifyAlive * HOUR)) {
                            timeLastPOAlive = Misc.getTime();
                            String aliveScreenName = saveGameScreen("alive-screen");
                            File aliveScreenFile = aliveScreenName != null ? new File(aliveScreenName) : null;

                            StringBuilder aliveMsg = new StringBuilder();
                            aliveMsg.append("I am alive and doing fine since ")
                                    .append(Misc.millisToHumanForm(Misc.getTime() - botStartTime))
                                    .append("!\n\n");

                            for (State state : State.values()) {
                                if (counters.get(state).getTotal() > 0) {
                                    aliveMsg.append(state.getName()).append(" ")
                                            .append(counters.get(state).successRateDesc())
                                            .append("\n");
                                }
                            }

                            sendPushOverMessage("Alive notification", aliveMsg.toString(), MessagePriority.QUIET, aliveScreenFile);
                            if (aliveScreenFile != null && !aliveScreenFile.delete())
                                BHBot.logger.warn("Impossible to delete tmp img for alive notification.");
                        }
                    }

                    // check for bonuses:
                    if (BHBot.settings.autoConsume && (Misc.getTime() - timeLastBonusCheck > BONUS_CHECK_INTERVAL)) {
                        timeLastBonusCheck = Misc.getTime();
                        handleConsumables();
                    }

                    //uncommentcomment for faster launching while testing
//					oneTimeshrineCheck = true;
//					oneTimeRuneCheck = true;

                    // One time check for Autoshrine
                    if (!oneTimeshrineCheck) {

                        BHBot.logger.info("Startup check to make sure autoShrine is initially disabled");
                        if (!checkShrineSettings(false, false)) {
                            BHBot.logger.error("It was not possible to perform the autoShrine start-up check!");
                        }
                        oneTimeshrineCheck = true;
                        BHBot.browser.readScreen(2 * SECOND); // delay to close the settings window completely before we check for raid button else the settings window is hiding it
                    }

                    // One time check for equipped minor runes
                    if (!BHBot.settings.autoRuneDefault.isEmpty() && !oneTimeRuneCheck) {

                        BHBot.logger.info("Startup check to determined configured minor runes");
                        if (!detectEquippedMinorRunes(true, true)) {
                            BHBot.logger.error("It was not possible to perform the equipped runes start-up check! Disabling autoRune..");
                            BHBot.settings.autoRuneDefault.clear();
                            BHBot.settings.autoRune.clear();
                            BHBot.settings.autoBossRune.clear();
                            continue;

                        }
                        BHBot.logger.info(getRuneName(leftMinorRune.getRuneCueName()) + " equipped in left slot.");
                        BHBot.logger.info(getRuneName(rightMinorRune.getRuneCueName()) + " equipped in right slot.");
                        oneTimeRuneCheck = true;
                        BHBot.browser.readScreen(2 * SECOND); // delay to close the settings window completely before we check for raid button else the settings window is hiding it
                    }

                    String currentActivity = activitySelector(); //else select the activity to attempt
                    if (currentActivity != null) {
                        BHBot.logger.debug("Checking activity: " + currentActivity);
                    } else {
                        // If we don't have any activity to perform, we reset the idle timer check
                        BHBot.scheduler.resetIdleTime(true);
                        continue;
                    }

                    // check for shards:
                    if ("r".equals(currentActivity)) {
                        timeLastShardsCheck = Misc.getTime();

                        BHBot.browser.readScreen();
                        MarvinSegment raidBTNSeg = MarvinSegment.fromCue(BrowserManager.cues.get("RaidButton"), BHBot.browser);

                        if (raidBTNSeg == null) { // if null, then raid button is transparent meaning that raiding is not enabled (we have not achieved it yet, for example)
                            BHBot.scheduler.restoreIdleTime();
                            continue;
                        }
                        BHBot.browser.clickOnSeg(raidBTNSeg);

                        seg = MarvinSegment.fromCue("RaidPopup", 5 * SECOND, BHBot.browser); // wait until the raid window opens
                        if (seg == null) {
                            BHBot.logger.warn("Error: attempt at opening raid window failed. No window cue detected. Ignoring...");
                            BHBot.scheduler.restoreIdleTime();
                            // we make sure that everything that can be closed is actually closed to avoid idle timeout
                            closePopupSecurely(BrowserManager.cues.get("X"), BrowserManager.cues.get("X"));
                            continue;
                        }


                        int shards = getShards();
                        globalShards = shards;
                        BHBot.logger.readout("Shards: " + shards + ", required: >" + BHBot.settings.minShards);

                        if (shards == -1) { // error
                            BHBot.scheduler.restoreIdleTime();
                            continue;
                        }

                        if ((shards == 0) || (!BHBot.scheduler.doRaidImmediately && (shards <= BHBot.settings.minShards || BHBot.settings.raids.size() == 0))) {
                            if (BHBot.scheduler.doRaidImmediately)
                                BHBot.scheduler.doRaidImmediately = false; // reset it

                            BHBot.browser.readScreen();
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            Misc.sleep(SECOND);

                            continue;

                        } else { // do the raiding!

                            if (BHBot.scheduler.doRaidImmediately)
                                BHBot.scheduler.doRaidImmediately = false; // reset it

                            //if we need to configure runes/settings we close the window first
                            if (BHBot.settings.autoShrine.contains("r") || BHBot.settings.autoRune.containsKey("r") || BHBot.settings.autoBossRune.containsKey("r")) {
                                BHBot.browser.readScreen();
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                BHBot.browser.readScreen(SECOND);
                            }

                            //autoshrine
                            if (BHBot.settings.autoShrine.contains("r")) {
                                BHBot.logger.info("Configuring autoShrine for Raid");
                                if (!checkShrineSettings(true, true)) {
                                    BHBot.logger.error("Impossible to configure autoShrine for Raid!");
                                }
                            }

                            //autoBossRune
                            if (BHBot.settings.autoBossRune.containsKey("r") && !BHBot.settings.autoShrine.contains("r")) { //if autoshrine disabled but autobossrune enabled
                                BHBot.logger.info("Configuring autoBossRune for Raid");
                                if (!checkShrineSettings(true, false)) {
                                    BHBot.logger.error("Impossible to configure autoBossRune for Raid!");
                                }
                            }

                            //activity runes
                            handleMinorRunes("r");

                            BHBot.browser.readScreen(SECOND);
                            BHBot.browser.clickOnSeg(raidBTNSeg);

                            String raid = decideRaidRandomly();
                            if (raid == null) {
                                BHBot.settings.activitiesEnabled.remove("r");
                                BHBot.logger.error("It was impossible to choose a raid randomly, raids are disabled!");
                                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                    sendPushOverMessage("Raid Error", "It was impossible to choose a raid randomly, raids are disabled!", "siren");
                                continue;
                            }

                            int difficulty = Integer.parseInt(raid.split(" ")[1]);
                            int desiredRaid = Integer.parseInt(raid.split(" ")[0]);

                            if (!handleRaidSelection(desiredRaid, difficulty)) {
                                restart();
                                continue;
                            }

                            BHBot.browser.readScreen(2 * SECOND);
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("RaidSummon"), 2 * SECOND, BHBot.browser);
                            if (seg == null) {
                                BHBot.logger.error("Raid Summon button not found");
                                restart();
                                continue;
                            }
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(2 * SECOND);

                            // dismiss character dialog if it pops up:
                            BHBot.browser.readScreen();
                            detectCharacterDialogAndHandleIt();

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get(difficulty == 1 ? "Normal" : difficulty == 2 ? "Hard" : "Heroic"), BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(2 * SECOND);
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), 5 * SECOND, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(2 * SECOND);

                            if (handleTeamMalformedWarning()) {
                                BHBot.logger.error("Team incomplete, doing emergency restart..");
                                restart();
                                continue;
                            } else {
                                state = State.Raid;
                                BHBot.logger.info("Raid initiated!");
                                autoShrined = false;
                                autoBossRuned = false;
                            }
                        }
                        continue;
                    } // shards

                    // check for tokens (trials and gauntlet):
                    if (BHBot.scheduler.doTrialsImmediately || BHBot.scheduler.doGauntletImmediately ||
                            ("t".equals(currentActivity)) || ("g".equals(currentActivity))) {
                        if ("t".equals(currentActivity)) timeLastTrialsTokensCheck = Misc.getTime();
                        if ("g".equals(currentActivity)) timeLastGauntletTokensCheck = Misc.getTime();

                        BHBot.browser.readScreen();

                        boolean trials;
                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Trials"), BHBot.browser);
                        if (seg == null) seg = MarvinSegment.fromCue(BrowserManager.cues.get("Trials2"), BHBot.browser);
                        trials = seg != null; // if false, then we will do gauntlet instead of trials

                        if (seg == null)
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Gauntlet"), BHBot.browser);
                        if (seg == null) {
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Gauntlet2"), BHBot.browser);
                        }
                        if (seg == null) {// trials/gauntlet button not visible (perhaps it is disabled?)
                            BHBot.logger.warn("Gauntlet/Trials button not found");
                            BHBot.scheduler.restoreIdleTime();
                            continue;
                        }

                        if (("g".equals(currentActivity) && trials) || ("t".equals(currentActivity) && !trials))
                            continue;


                        BHBot.browser.clickOnSeg(seg);
                        MarvinSegment trialBTNSeg = seg;

                        // dismiss character dialog if it pops up:
                        BHBot.browser.readScreen(2 * SECOND);
                        detectCharacterDialogAndHandleIt();

                        BHBot.browser.readScreen();
                        int tokens = getTokens();
                        globalTokens = tokens;
                        BHBot.logger.readout("Tokens: " + tokens + ", required: >" + BHBot.settings.minTokens + ", " +
                                (trials ? "Trials" : "Gauntlet") + " cost: " + (trials ? BHBot.settings.costTrials : BHBot.settings.costGauntlet));

                        if (tokens == -1) { // error
                            BHBot.scheduler.restoreIdleTime();
                            continue;
                        }

                        if (((!BHBot.scheduler.doTrialsImmediately && !BHBot.scheduler.doGauntletImmediately) && (tokens <= BHBot.settings.minTokens)) || (tokens < (trials ? BHBot.settings.costTrials : BHBot.settings.costGauntlet))) {
                            BHBot.browser.readScreen();
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(SECOND);

                            //if we have 1 token and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one token short
                            int tokenDifference = (trials ? BHBot.settings.costTrials : BHBot.settings.costGauntlet) - tokens; //difference between needed and current resource
                            if (tokenDifference > 1) {
                                int increase = (tokenDifference - 1) * 45;
                                TOKENS_CHECK_INTERVAL = increase * MINUTE; //add 45 minutes to TOKENS_CHECK_INTERVAL for each token needed above 1
                            } else TOKENS_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 token check every 10 minutes

                            if (BHBot.scheduler.doTrialsImmediately) {
                                BHBot.scheduler.doTrialsImmediately = false; // if we don't have resources to run we need to disable force it
                            } else if (BHBot.scheduler.doGauntletImmediately) {
                                BHBot.scheduler.doGauntletImmediately = false;
                            }

                            continue;
                        } else {
                            // do the trials/gauntlet!

                            if (BHBot.scheduler.doTrialsImmediately) {
                                BHBot.scheduler.doTrialsImmediately = false; // reset it
                            } else if (BHBot.scheduler.doGauntletImmediately) {
                                BHBot.scheduler.doGauntletImmediately = false;
                            }

                            // One time check for Autoshrine
                            //wait for window animation
                            if (trials) {
                                //if we need to configure runes/settings we close the window first
                                if (BHBot.settings.autoShrine.contains("t") || BHBot.settings.autoRune.containsKey("t") || BHBot.settings.autoBossRune.containsKey("t")) {
                                    BHBot.browser.readScreen();
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                    BHBot.browser.clickOnSeg(seg);
                                    BHBot.browser.readScreen(SECOND);
                                }

                                //autoshrine
                                if (BHBot.settings.autoShrine.contains("t")) {
                                    BHBot.logger.info("Configuring autoShrine for Trials");
                                    if (!checkShrineSettings(true, true)) {
                                        BHBot.logger.error("Impossible to configure autoShrine for Trials!");
                                    }
                                }

                                //autoBossRune
                                if (BHBot.settings.autoBossRune.containsKey("t") && !BHBot.settings.autoShrine.contains("t")) { //if autoshrine disabled but autobossrune enabled
                                    BHBot.logger.info("Configuring autoBossRune for Trials");
                                    if (!checkShrineSettings(true, false)) {
                                        BHBot.logger.error("Impossible to configure autoBossRune for Trials!");
                                    }
                                }

                                //activity runes
                                handleMinorRunes("t");

                            } else {

                                if (BHBot.settings.autoRune.containsKey("g")) {
                                    handleMinorRunes("g");
                                    BHBot.browser.readScreen(SECOND);
                                }

                            }
                            BHBot.browser.readScreen(SECOND);
                            BHBot.browser.clickOnSeg(trialBTNSeg);
                            BHBot.browser.readScreen(SECOND); //wait for window animation

                            // apply the correct difficulty
                            int targetDifficulty = trials ? BHBot.settings.difficultyTrials : BHBot.settings.difficultyGauntlet;

                            BHBot.logger.info("Attempting " + (trials ? "trials" : "gauntlet") + " at level " + targetDifficulty + "...");

                            int difficulty = detectDifficulty();
                            if (difficulty == 0) { // error!
                                BHBot.logger.error("Due to an error#1 in difficulty detection, " + (trials ? "trials" : "gauntlet") + " will be skipped.");
                                closePopupSecurely(BrowserManager.cues.get("TrialsOrGauntletWindow"), BrowserManager.cues.get("X"));
                                continue;
                            }
                            if (difficulty != targetDifficulty) {
                                BHBot.logger.info("Detected " + (trials ? "trials" : "gauntlet") + " difficulty level: " + difficulty + ", settings level: " + targetDifficulty + ". Changing..");
                                boolean result = selectDifficulty(difficulty, targetDifficulty);
                                if (!result) { // error!
                                    // see if drop down menu is still open and close it:
                                    BHBot.browser.readScreen(SECOND);
                                    tryClosingWindow(BrowserManager.cues.get("DifficultyDropDown"));
                                    BHBot.browser.readScreen(5 * SECOND);
                                    BHBot.logger.warn("Unable to change difficulty, usually because desired level is not unlocked. Running " + (trials ? "trials" : "gauntlet") + " at " + difficulty + ".");
                                    sendPushOverMessage("T/G Error", "Unable to change difficulty to : " + targetDifficulty + " Running: " + difficulty + " instead.", "siren");
                                }
                            }

                            // select cost if needed:
                            BHBot.browser.readScreen(2 * SECOND); // wait for the popup to stabilize a bit
                            int cost = detectCost();
                            if (cost == 0) { // error!
                                BHBot.logger.error("Due to an error#1 in cost detection, " + (trials ? "trials" : "gauntlet") + " will be skipped.");
                                closePopupSecurely(BrowserManager.cues.get("TrialsOrGauntletWindow"), BrowserManager.cues.get("X"));
                                continue;
                            }
                            if (cost != (trials ? BHBot.settings.costTrials : BHBot.settings.costGauntlet)) {
                                BHBot.logger.info("Detected " + (trials ? "trials" : "gauntlet") + " cost: " + cost + ", settings cost is " + (trials ? BHBot.settings.costTrials : BHBot.settings.costGauntlet) + ". Changing it...");
                                boolean result = selectCost(cost, (trials ? BHBot.settings.costTrials : BHBot.settings.costGauntlet));
                                if (!result) { // error!
                                    // see if drop down menu is still open and close it:
                                    BHBot.browser.readScreen(SECOND);
                                    tryClosingWindow(BrowserManager.cues.get("CostDropDown"));
                                    BHBot.browser.readScreen(5 * SECOND);
                                    tryClosingWindow(BrowserManager.cues.get("TrialsOrGauntletWindow"));
                                    BHBot.logger.error("Due to an error#2 in cost selection, " + (trials ? "trials" : "gauntlet") + " will be skipped.");
                                    continue;
                                }

                                // We wait for the cost selector window to close
                                MarvinSegment.fromCue("TrialsOrGauntletWindow", SECOND * 2, BHBot.browser);
                                BHBot.browser.readScreen();
                            }

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Play"), 2 * SECOND, BHBot.browser);
                            if (seg == null) {
                                BHBot.logger.error("Error: Play button not found while trying to do " + (trials ? "trials" : "gauntlet") + ". Ignoring...");
                                tryClosingWindow(BrowserManager.cues.get("TrialsOrGauntletWindow"));
                                continue;
                            }
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(2 * SECOND);

                            if (!handleNotEnoughTokensPopup(false)) {
                                restart();
                                continue;
                            }

                            // dismiss character dialog if it pops up:
                            detectCharacterDialogAndHandleIt();

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), 5 * SECOND, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(2 * SECOND);

                            // This is a Bit Heroes bug!
                            // On t/g main screen the token bar is wrongly full so it goes trough the "Play" button and
                            // then it fails on the team "Accept" button
                            if (!handleNotEnoughTokensPopup(true)) {
                                restart();
                                continue;
                            }

                            Misc.sleep(3 * SECOND);

                            if (handleTeamMalformedWarning()) {
                                BHBot.logger.error("Team incomplete, doing emergency restart..");
                                restart();
                                continue;
                            } else {
                                state = trials ? State.Trials : State.Gauntlet;
                                BHBot.logger.info((trials ? "Trials" : "Gauntlet") + " initiated!");
                                autoShrined = false;
                                autoBossRuned = false;
                            }
                        }
                        continue;
                    } // tokens (trials and gauntlet)

                    // check for energy:
                    if ("d".equals(currentActivity)) {
                        timeLastEnergyCheck = Misc.getTime();

                        BHBot.browser.readScreen();

                        int energy = getEnergy();
                        globalEnergy = energy;
                        BHBot.logger.readout("Energy: " + energy + "%, required: >" + BHBot.settings.minEnergyPercentage + "%");

                        if (energy == -1) { // error
                            BHBot.scheduler.restoreIdleTime();
                            if (BHBot.scheduler.doDungeonImmediately)
                                BHBot.scheduler.doDungeonImmediately = false; // reset it
                            continue;
                        }

                        if (!BHBot.scheduler.doDungeonImmediately && (energy <= BHBot.settings.minEnergyPercentage || BHBot.settings.dungeons.size() == 0)) {
                            Misc.sleep(SECOND);

                            //if we have 1 resource and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one under the check limit
                            int energyDifference = BHBot.settings.minEnergyPercentage - energy; //difference between needed and current resource
                            if (energyDifference > 1) {
                                int increase = (energyDifference - 1) * 8;
                                ENERGY_CHECK_INTERVAL = increase * MINUTE; //add 8 minutes to the check interval for each energy % needed above 1
                            } else ENERGY_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 check every 10 minutes

                            continue;
                        } else {
                            // do the dungeon!

                            if (BHBot.scheduler.doDungeonImmediately)
                                BHBot.scheduler.doDungeonImmediately = false; // reset it

                            //configure activity runes
                            handleMinorRunes("d");

                            if (BHBot.settings.autoBossRune.containsKey("d") && !BHBot.settings.autoShrine.contains("d")) { //if autoshrine disabled but autorune enabled

                                BHBot.logger.info("Configuring autoBossRune for Dungeons");
                                if (!checkShrineSettings(true, false)) {
                                    BHBot.logger.error("Impossible to configure autoBossRune for Dungeons!");
                                }

                                BHBot.browser.readScreen(SECOND);
                                Misc.sleep(2 * SECOND);
                            }

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Quest"), BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(5 * SECOND);

                            String dungeon = decideDungeonRandomly();
                            if (dungeon == null) {
                                BHBot.settings.activitiesEnabled.remove("d");
                                BHBot.logger.error("It was impossible to choose a dungeon randomly, dungeons are disabled!");
                                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                    sendPushOverMessage("Dungeon error", "It was impossible to choose a dungeon randomly, dungeons are disabled!", "siren");
                                continue;
                            }

                            Matcher dungeonMatcher = dungeonRegex.matcher(dungeon.toLowerCase());
                            if (!dungeonMatcher.find()) {
                                BHBot.logger.error("Wrong format in dungeon detected: " + dungeon + "! It will be skipped...");
                                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                    sendPushOverMessage("Dungeon error", "Wrong dungeon format detected: " + dungeon, "siren");
                                continue;
                            }

                            int goalZone = Integer.parseInt(dungeonMatcher.group("zone"));
                            int goalDungeon = Integer.parseInt(dungeonMatcher.group("dungeon"));
                            int difficulty = Integer.parseInt(dungeonMatcher.group("difficulty"));

                            String difficultyName = (difficulty == 1 ? "Normal" : difficulty == 2 ? "Hard" : "Heroic");

                            BHBot.logger.info("Attempting " + difficultyName + " z" + goalZone + "d" + goalDungeon);

                            int currentZone = readCurrentZone();
                            int vec = goalZone - currentZone; // movement vector
//							BHBot.logger.info("Current zone: " + Integer.toString(currentZone) + " Target Zone: " + Integer.toString(goalZone));
                            while (vec != 0) { // move to the correct zone
                                if (vec > 0) {
                                    // note that moving to the right will fail in case player has not unlocked the zone yet!
                                    BHBot.browser.readScreen(SECOND); // wait for screen to stabilise
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("RightArrow"), BHBot.browser);
                                    if (seg == null) {
                                        BHBot.logger.error("Right button not found, zone unlocked?");
                                        break; // happens for example when player hasn't unlock the zone yet
                                    }
                                    //coords are used as moving multiple screens would crash the bot when using the arrow cues
                                    BHBot.browser.clickInGame(740, 275);
                                    vec--;
                                } else {
                                    Misc.sleep(500);
                                    //coords are used as moving multiple screens would crash the bot when using the arrow cues
                                    BHBot.browser.clickInGame(55, 275);
                                    vec++;
                                }
                            }

                            BHBot.browser.readScreen(2 * SECOND);

                            // click on the dungeon:
                            Point p = getDungeonIconPos(goalZone, goalDungeon);
                            if (p == null) {
                                BHBot.settings.activitiesEnabled.remove("d");
                                BHBot.logger.error("It was impossible to get icon position of dungeon z" + goalZone + "d" + goalDungeon + ". Dungeons are now disabled!");
                                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                    sendPushOverMessage("Dungeon error", "It was impossible to get icon position of dungeon z" + goalZone + "d" + goalDungeon + ". Dungeons are now disabled!", "siren");
                                continue;
                            }

                            BHBot.browser.clickInGame(p.x, p.y);

                            BHBot.browser.readScreen(3 * SECOND);
                            // select difficulty (If D4 just hit enter):
                            if ((goalDungeon == 4) || (goalZone == 7 && goalDungeon == 3) || (goalZone == 8 && goalDungeon == 3)) { // D4, or Z7D3/Z8D3
                                specialDungeon = true;
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Enter"), 5 * SECOND, BHBot.browser);
                            } else { //else select appropriate difficulty
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get(difficulty == 1 ? "Normal" : difficulty == 2 ? "Hard" : "Heroic"), 5 * SECOND, BHBot.browser);
                            }
                            BHBot.browser.clickOnSeg(seg);

                            //team selection screen
                            /* Solo-for-bounty code */
                            if (goalZone <= BHBot.settings.minSolo) { //if the level is soloable then clear the team to complete bounties
                                BHBot.browser.readScreen(SECOND);
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Clear"), SECOND * 2, BHBot.browser);
                                if (seg != null) {
                                    BHBot.logger.info("Selected zone under dungeon solo threshold, attempting solo");
                                    BHBot.browser.clickOnSeg(seg);
                                } else {
                                    BHBot.logger.error("Impossible to find clear button in Dungeon Team!");
                                    restart();
                                    continue;
                                }
                            }

                            BHBot.browser.readScreen();
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), SECOND * 2, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);

                            if (goalZone <= BHBot.settings.minSolo) {
                                BHBot.browser.readScreen(3 * SECOND); //wait for dropdown animation to finish
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 2 * SECOND, BHBot.browser);
                                if (seg != null) {
                                    BHBot.browser.clickOnSeg(seg);
                                } else {
                                    BHBot.logger.error("Impossible to find Yes button in Dungeon Team!");
                                    restart();
                                }
                            } else {
                                if (handleTeamMalformedWarning()) {
                                    restart();
                                    continue;
                                }
                            }

                            if (handleNotEnoughEnergyPopup(3 * SECOND, State.Dungeon)) {
                                continue;
                            }

                            state = State.Dungeon;
                            autoShrined = false;
                            autoBossRuned = false;

                            BHBot.logger.info("Dungeon <z" + goalZone + "d" + goalDungeon + "> " + (difficulty == 1 ? "normal" : difficulty == 2 ? "hard" : "heroic") + " initiated!");
                        }
                        continue;
                    } // energy

                    // check for Tickets (PvP):
                    if ("p".equals(currentActivity)) {
                        timeLastTicketsCheck = Misc.getTime();

                        BHBot.browser.readScreen();

                        int tickets = getTickets();
                        globalTickets = tickets;
                        BHBot.logger.readout("Tickets: " + tickets + ", required: >" + BHBot.settings.minTickets + ", PVP cost: " + BHBot.settings.costPVP);

                        if (tickets == -1) { // error
                            BHBot.scheduler.restoreIdleTime();
                            continue;
                        }

                        if ((!BHBot.scheduler.doPVPImmediately && (tickets <= BHBot.settings.minTickets)) || (tickets < BHBot.settings.costPVP)) {
                            Misc.sleep(SECOND);

                            //if we have 1 resource and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one under the check limit
                            int ticketDifference = BHBot.settings.costPVP - tickets; //difference between needed and current resource
                            if (ticketDifference > 1) {
                                int increase = (ticketDifference - 1) * 45;
                                TICKETS_CHECK_INTERVAL = increase * MINUTE; //add 45 minutes to the check interval for each ticket needed above 1
                            } else TICKETS_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 check every 10 minutes

                            continue;
                        } else {
                            // do the pvp!

                            if (BHBot.scheduler.doPVPImmediately)
                                BHBot.scheduler.doPVPImmediately = false; // reset it

                            //configure activity runes
                            handleMinorRunes("p");

                            BHBot.logger.info("Attempting PVP...");
                            stripDown(BHBot.settings.pvpstrip);

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("PVP"), BHBot.browser);
                            if (seg == null) {
                                BHBot.logger.warn("PVP button not found. Skipping PVP...");
                                dressUp(BHBot.settings.pvpstrip);
                                continue; // should not happen though
                            }
                            BHBot.browser.clickOnSeg(seg);

                            // select cost if needed:
                            BHBot.browser.readScreen(2 * SECOND); // wait for the popup to stabilize a bit
                            int cost = detectCost();
                            if (cost == 0) { // error!
                                BHBot.logger.error("Due to an error#1 in cost detection, PVP will be skipped.");
                                closePopupSecurely(BrowserManager.cues.get("PVPWindow"), BrowserManager.cues.get("X"));
                                dressUp(BHBot.settings.pvpstrip);
                                continue;
                            }
                            if (cost != BHBot.settings.costPVP) {
                                BHBot.logger.info("Detected PVP cost: " + cost + ", settings cost is " + BHBot.settings.costPVP + ". Changing..");
                                boolean result = selectCost(cost, BHBot.settings.costPVP);
                                if (!result) { // error!
                                    // see if drop down menu is still open and close it:
                                    BHBot.browser.readScreen(SECOND);
                                    tryClosingWindow(BrowserManager.cues.get("CostDropDown"));
                                    BHBot.browser.readScreen(5 * SECOND);
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("PVPWindow"), 15 * SECOND, BHBot.browser);
                                    if (seg != null)
                                        closePopupSecurely(BrowserManager.cues.get("PVPWindow"), BrowserManager.cues.get("X"));
                                    BHBot.logger.error("Due to an error#2 in cost selection, PVP will be skipped.");
                                    dressUp(BHBot.settings.pvpstrip);
                                    continue;
                                }
                            }

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Play"), 5 * SECOND, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(2 * SECOND);

                            // dismiss character dialog if it pops up:
                            detectCharacterDialogAndHandleIt();

                            Bounds pvpOpponentBounds = opponentSelector(BHBot.settings.pvpOpponent);
                            String opponentName = (BHBot.settings.pvpOpponent == 1 ? "1st" : BHBot.settings.pvpOpponent == 2 ? "2nd" : BHBot.settings.pvpOpponent == 3 ? "3rd" : "4th");
                            BHBot.logger.info("Selecting " + opponentName + " opponent");
                            seg = MarvinSegment.fromCue("Fight", 5 * SECOND, pvpOpponentBounds, BHBot.browser);
                            if (seg == null) {
                                BHBot.logger.error("Imppossible to find the Fight button in the PVP screen, restarting!");
                                restart();
                                continue;
                            }
                            BHBot.browser.clickOnSeg(seg);

                            BHBot.browser.readScreen();
                            seg = MarvinSegment.fromCue("Accept", 5 * SECOND, new Bounds(430, 430, 630, 500), BHBot.browser);
                            if (seg == null) {
                                BHBot.logger.error("Impossible to find the Accept button in the PVP screen, restarting");
                                restart();
                                continue;
                            }
                            BHBot.browser.clickOnSeg(seg);

                            if (handleTeamMalformedWarning()) {
                                BHBot.logger.error("Team incomplete, doing emergency restart..");
                                restart();
                                continue;
                            } else {
                                state = State.PVP;
                                BHBot.logger.info("PVP initiated!");
                            }
                        }
                        continue;
                    } // PvP

                    // check for badges (for GVG/Invasion/Expedition):
                    if (("v".equals(currentActivity)) || ("i".equals(currentActivity)) || ("e".equals(currentActivity))) {

                        String checkedActivity = currentActivity;

                        if ("v".equals(currentActivity)) timeLastGVGBadgesCheck = Misc.getTime();
                        if ("i".equals(currentActivity)) timeLastInvBadgesCheck = Misc.getTime();
                        if ("e".equals(currentActivity)) timeLastExpBadgesCheck = Misc.getTime();

                        BHBot.browser.readScreen();

                        BadgeEvent badgeEvent = BadgeEvent.None;
                        MarvinSegment badgeBtn = null;

                        HashMap<Cue, BadgeEvent> badgeEvents = new HashMap<>();
                        badgeEvents.put(BrowserManager.cues.get("ExpeditionButton"), BadgeEvent.Expedition);
                        badgeEvents.put(BrowserManager.cues.get("GVG"), BadgeEvent.GVG);
                        badgeEvents.put(BrowserManager.cues.get("Invasion"), BadgeEvent.Invasion);

                        for (Map.Entry<Cue, BadgeEvent> event : badgeEvents.entrySet()) {
                            badgeBtn = MarvinSegment.fromCue(event.getKey(), BHBot.browser);
                            if (badgeBtn != null) {
                                badgeEvent = event.getValue();
                                seg = badgeBtn;
                                break;
                            }
                        }


                        if (badgeEvent == BadgeEvent.None) { // GvG/invasion button not visible (perhaps this week there is no GvG/Invasion/Expedition event?)
                            BHBot.scheduler.restoreIdleTime();
                            BHBot.logger.debug("No badge event found, skipping");
                            continue;
                        }

                        if (badgeEvent == BadgeEvent.Expedition) currentActivity = "e";
                        if (badgeEvent == BadgeEvent.Invasion) currentActivity = "i";
                        if (badgeEvent == BadgeEvent.GVG) currentActivity = "v";

                        if (!currentActivity.equals(checkedActivity)) { //if checked activity and chosen activity don't match we skip
                            continue;
                        }

                        BHBot.browser.clickOnSeg(seg);
                        Misc.sleep(2 * SECOND);

                        detectCharacterDialogAndHandleIt(); // needed for invasion

                        BHBot.browser.readScreen();
                        int badges = getBadges();
                        globalBadges = badges;
                        BHBot.logger.readout("Badges: " + badges + ", required: >" + BHBot.settings.minBadges + ", " + badgeEvent.toString() + " cost: " +
                                (badgeEvent == BadgeEvent.GVG ? BHBot.settings.costGVG : badgeEvent == BadgeEvent.Invasion ? BHBot.settings.costInvasion : BHBot.settings.costExpedition));

                        if (badges == -1) { // error
                            BHBot.scheduler.restoreIdleTime();
                            continue;
                        }

                        // check GVG:
                        if (badgeEvent == BadgeEvent.GVG) {
                            if ((!BHBot.scheduler.doGVGImmediately && (badges <= BHBot.settings.minBadges)) || (badges < BHBot.settings.costGVG)) {

                                //if we have 1 resource and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one under the check limit
                                int badgeDifference = BHBot.settings.costGVG - badges; //difference between needed and current resource
                                if (badgeDifference > 1) {
                                    int increase = (badgeDifference - 1) * 45;
                                    BADGES_CHECK_INTERVAL = increase * MINUTE; //add 45 minutes to the check interval for each ticket needed above 1
                                } else BADGES_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 check every 10 minutes

                                BHBot.browser.readScreen();
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                Misc.sleep(SECOND);
                                continue;
                            } else {
                                // do the GVG!

                                if (BHBot.scheduler.doGVGImmediately)
                                    BHBot.scheduler.doGVGImmediately = false; // reset it


                                //configure activity runes
                                handleMinorRunes("v");
                                BHBot.browser.readScreen(SECOND);
                                BHBot.browser.clickOnSeg(badgeBtn);

                                BHBot.logger.info("Attempting GVG...");

                                if (BHBot.settings.gvgstrip.size() > 0) {
                                    // If we need to strip down for GVG, we need to close the GVG gump and open it again
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND * 2, BHBot.browser);
                                    BHBot.browser.clickOnSeg(seg);
                                    BHBot.browser.readScreen(2 * SECOND);
                                    stripDown(BHBot.settings.gvgstrip);
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("GVG"), SECOND * 3, BHBot.browser);
                                    BHBot.browser.clickOnSeg(seg);
                                }

                                // select cost if needed:
                                BHBot.browser.readScreen(2 * SECOND); // wait for the popup to stabilize a bit
                                int cost = detectCost();
                                if (cost == 0) { // error!
                                    BHBot.logger.error("Due to an error#1 in cost detection, GVG will be skipped.");
                                    closePopupSecurely(BrowserManager.cues.get("GVGWindow"), BrowserManager.cues.get("X"));
                                    continue;
                                }
                                if (cost != BHBot.settings.costGVG) {
                                    BHBot.logger.info("Detected GVG cost: " + cost + ", settings cost is " + BHBot.settings.costGVG + ". Changing..");
                                    boolean result = selectCost(cost, BHBot.settings.costGVG);
                                    if (!result) { // error!
                                        // see if drop down menu is still open and close it:
                                        BHBot.browser.readScreen(SECOND);
                                        tryClosingWindow(BrowserManager.cues.get("CostDropDown"));
                                        BHBot.browser.readScreen(5 * SECOND);
                                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("GVGWindow"), 15 * SECOND, BHBot.browser);
                                        if (seg != null)
                                            closePopupSecurely(BrowserManager.cues.get("GVGWindow"), BrowserManager.cues.get("X"));
                                        BHBot.logger.error("Due to an error#2 in cost selection, GVG will be skipped.");
                                        dressUp(BHBot.settings.gvgstrip);
                                        continue;
                                    }
                                }


                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Play"), 5 * SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                BHBot.browser.readScreen(2 * SECOND);

                                // Sometimes, before the reset, battles are disabled
                                Boolean disabledBattles = handleDisabledBattles();
                                if (disabledBattles == null) {
                                    restart();
                                    continue;
                                } else if (disabledBattles) {
                                    BHBot.browser.readScreen();
                                    closePopupSecurely(BrowserManager.cues.get("GVGWindow"), BrowserManager.cues.get("X"));
                                    continue;
                                }

                                //On initial GvG run you'll get a warning about not being able to leave guild, this will close that
                                if (handleGuildLeaveConfirm()) {
                                    restart();
                                    continue;
                                }

                                Bounds gvgOpponentBounds = opponentSelector(BHBot.settings.gvgOpponent);
                                String opponentName = (BHBot.settings.gvgOpponent == 1 ? "1st" : BHBot.settings.gvgOpponent == 2 ? "2nd" : BHBot.settings.gvgOpponent == 3 ? "3rd" : "4th");
                                BHBot.logger.info("Selecting " + opponentName + " opponent");
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Fight"), 5 * SECOND, gvgOpponentBounds, BHBot.browser);
                                if (seg == null) {
                                    BHBot.logger.error("Imppossible to find the Fight button in the GvG screen, restarting!");
                                    restart();
                                    continue;
                                }
                                BHBot.browser.clickOnSeg(seg);
                                BHBot.browser.readScreen();
                                Misc.sleep(SECOND);

                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), 2 * SECOND, BHBot.browser);
                                if (seg == null) {
                                    BHBot.logger.error("Imppossible to find the Accept button in the GvG screen, restarting!");
                                    restart();
                                    continue;
                                }
                                BHBot.browser.clickOnSeg(seg);
                                Misc.sleep(SECOND);

                                if (handleTeamMalformedWarning()) {
                                    BHBot.logger.error("Team incomplete, doing emergency restart..");
                                    restart();
                                    continue;
                                } else {
                                    state = State.GVG;
                                    BHBot.logger.info("GVG initiated!");
                                }
                            }
                            continue;
                        } // GvG
                        // check invasion:
                        else if (badgeEvent == BadgeEvent.Invasion) {
                            if ((!BHBot.scheduler.doInvasionImmediately && (badges <= BHBot.settings.minBadges)) || (badges < BHBot.settings.costInvasion)) {

                                //if we have 1 resource and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one under the check limit
                                int badgeDifference = BHBot.settings.costGVG - badges; //difference between needed and current resource
                                if (badgeDifference > 1) {
                                    int increase = (badgeDifference - 1) * 45;
                                    BADGES_CHECK_INTERVAL = increase * MINUTE; //add 45 minutes to the check interval for each ticket needed above 1
                                } else BADGES_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 check every 10 minutes

                                BHBot.browser.readScreen();
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                Misc.sleep(SECOND);
                                continue;
                            } else {
                                // do the invasion!

                                if (BHBot.scheduler.doInvasionImmediately)
                                    BHBot.scheduler.doInvasionImmediately = false; // reset it

                                //configure activity runes
                                handleMinorRunes("i");
                                BHBot.browser.readScreen(SECOND);
                                BHBot.browser.clickOnSeg(badgeBtn);

                                BHBot.logger.info("Attempting invasion...");

                                // select cost if needed:
                                BHBot.browser.readScreen(2 * SECOND); // wait for the popup to stabilize a bit
                                int cost = detectCost();
                                if (cost == 0) { // error!
                                    BHBot.logger.error("Due to an error#1 in cost detection, invasion will be skipped.");
                                    closePopupSecurely(BrowserManager.cues.get("InvasionWindow"), BrowserManager.cues.get("X"));
                                    continue;
                                }
                                if (cost != BHBot.settings.costInvasion) {
                                    BHBot.logger.info("Detected invasion cost: " + cost + ", settings cost is " + BHBot.settings.costInvasion + ". Changing..");
                                    boolean result = selectCost(cost, BHBot.settings.costInvasion);
                                    if (!result) { // error!
                                        // see if drop down menu is still open and close it:
                                        BHBot.browser.readScreen(SECOND);
                                        tryClosingWindow(BrowserManager.cues.get("CostDropDown"));
                                        BHBot.browser.readScreen(5 * SECOND);
                                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("InvasionWindow"), 15 * SECOND, BHBot.browser);
                                        if (seg != null)
                                            closePopupSecurely(BrowserManager.cues.get("InvasionWindow"), BrowserManager.cues.get("X"));
                                        BHBot.logger.error("Due to an error#2 in cost selection, invasion will be skipped.");
                                        continue;
                                    }
                                }

                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Play"), 5 * SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);

                                BHBot.browser.readScreen(3000);
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), BHBot.browser);
                                if (seg == null) {
                                    BHBot.logger.error("Unable to find the Accept button in the Invasion screen, restarting!");
                                    restart();
                                    continue;
                                }
                                BHBot.browser.clickOnSeg(seg);
                                Misc.sleep(2 * SECOND);

                                if (handleTeamMalformedWarning()) {
                                    BHBot.logger.error("Team incomplete, doing emergency restart..");
                                    restart();
                                    continue;
                                } else {
                                    state = State.Invasion;
                                    BHBot.logger.info("Invasion initiated!");
                                    autoShrined = false;
                                }
                            }
                            continue;
                        } // invasion

                        // check Expedition
                        else if (badgeEvent == BadgeEvent.Expedition) {

                            if ((!BHBot.scheduler.doExpeditionImmediately && (badges <= BHBot.settings.minBadges)) || (badges < BHBot.settings.costExpedition)) {

                                //if we have 1 resource and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one under the check limit
                                int badgeDifference = BHBot.settings.costGVG - badges; //difference between needed and current resource
                                if (badgeDifference > 1) {
                                    int increase = (badgeDifference - 1) * 45;
                                    BADGES_CHECK_INTERVAL = increase * MINUTE; //add 45 minutes to the check interval for each ticket needed above 1
                                } else BADGES_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 check every 10 minutes

                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                Misc.sleep(2 * SECOND);
                                continue;
                            } else {
                                // do the expedition!

                                if (BHBot.scheduler.doExpeditionImmediately)
                                    BHBot.scheduler.doExpeditionImmediately = false; // reset it

                                if (BHBot.settings.costExpedition > badges) {
                                    BHBot.logger.info("Target cost " + BHBot.settings.costExpedition + " is higher than available badges " + badges + ". Expedition will be skipped.");
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                    BHBot.browser.clickOnSeg(seg);
                                    Misc.sleep(2 * SECOND);
                                    continue;
                                }

                                //if we need to configure runes/settings we close the window first
                                if (BHBot.settings.autoShrine.contains("e") || BHBot.settings.autoRune.containsKey("e") || BHBot.settings.autoBossRune.containsKey("e")) {
                                    BHBot.browser.readScreen();
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                    BHBot.browser.clickOnSeg(seg);
                                    BHBot.browser.readScreen(SECOND);
                                }

                                //autoshrine
                                if (BHBot.settings.autoShrine.contains("e")) {
                                    BHBot.logger.info("Configuring autoShrine for Expedition");
                                    if (!checkShrineSettings(true, true)) {
                                        BHBot.logger.error("Impossible to configure autoShrine for Expedition!");
                                    }
                                }

                                //autoBossRune
                                if (BHBot.settings.autoBossRune.containsKey("e") && !BHBot.settings.autoShrine.contains("e")) { //if autoshrine disabled but autobossrune enabled
                                    BHBot.logger.info("Configuring autoBossRune for Expedition");
                                    if (!checkShrineSettings(true, false)) {
                                        BHBot.logger.error("Impossible to configure autoBossRune for Expedition!");
                                    }
                                }

                                //activity runes
                                handleMinorRunes("e");

                                BHBot.browser.readScreen(SECOND);
                                BHBot.browser.clickOnSeg(badgeBtn);
                                BHBot.browser.readScreen(SECOND * 2);

                                BHBot.logger.info("Attempting expedition...");

                                BHBot.browser.readScreen(SECOND * 2);
                                int cost = detectCost();
                                if (cost == 0) { // error!
                                    BHBot.logger.error("Due to an error#1 in cost detection, Expedition cost will be skipped.");
                                    closePopupSecurely(BrowserManager.cues.get("ExpeditionWindow"), BrowserManager.cues.get("X"));
                                    continue;
                                }

                                if (cost != BHBot.settings.costExpedition) {
                                    BHBot.logger.info("Detected Expedition cost: " + cost + ", settings cost is " + BHBot.settings.costExpedition + ". Changing..");
                                    boolean result = selectCost(cost, BHBot.settings.costExpedition);
                                    if (!result) { // error!
                                        // see if drop down menu is still open and close it:
                                        BHBot.browser.readScreen(SECOND);
                                        tryClosingWindow(BrowserManager.cues.get("CostDropDown"));
                                        BHBot.browser.readScreen(5 * SECOND);
                                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                        BHBot.browser.clickOnSeg(seg);
                                        Misc.sleep(2 * SECOND);
                                        BHBot.logger.error("Due to an error in cost selection, Expedition will be skipped.");
                                        continue;
                                    }
                                    BHBot.browser.readScreen(SECOND * 2);
                                }

                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Play"), 2 * SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                BHBot.browser.readScreen(2 * SECOND);

                                //Select Expedition and write portal to a variable
                                String randomExpedition = BHBot.settings.expeditions.next();
                                if (randomExpedition == null) {
                                    BHBot.settings.activitiesEnabled.remove("e");
                                    BHBot.logger.error("It was impossible to randomly choose an expedition. Expeditions are disabled.");
                                    if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                        sendPushOverMessage("Expedition error", "It was impossible to randomly choose an expedition. Expeditions are disabled.", "siren");
                                    continue;
                                }

                                String[] expedition = randomExpedition.split(" ");
                                String targetPortal = expedition[0];
                                int targetDifficulty = Integer.parseInt(expedition[1]);

                                // if exped difficulty isn't a multiple of 5 we reduce it
                                int difficultyModule = targetDifficulty % 5;
                                if (difficultyModule != 0) {
                                    BHBot.logger.warn(targetDifficulty + " is not a multiplier of 5! Rounding it to " + (targetDifficulty - difficultyModule) + "...");
                                    targetDifficulty -= difficultyModule;
                                }
                                // If difficulty is lesser that 5, we round it
                                if (targetDifficulty < 5) {
                                    BHBot.logger.warn("Expedition difficulty can not be smaller than 5, rounding it to 5.");
                                    targetDifficulty = 5;
                                }

                                BHBot.browser.readScreen();
                                int currentExpedition;
                                if (MarvinSegment.fromCue(BrowserManager.cues.get("Expedition1"), BHBot.browser) != null) {
                                    currentExpedition = 1;
                                } else if (MarvinSegment.fromCue(BrowserManager.cues.get("Expedition2"), BHBot.browser) != null) {
                                    currentExpedition = 2;
                                } else if (MarvinSegment.fromCue(BrowserManager.cues.get("Expedition3"), BHBot.browser) != null) {
                                    currentExpedition = 3;
                                } else if (MarvinSegment.fromCue(BrowserManager.cues.get("Expedition4"), BHBot.browser) != null) {
                                    currentExpedition = 4;
                                } else if (MarvinSegment.fromCue("Expedition5", BHBot.browser) != null) {
                                    currentExpedition = 5;
                                } else {
                                    BHBot.settings.activitiesEnabled.remove("e");
                                    BHBot.logger.error("It was impossible to get the current expedition type!");
                                    if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                        sendPushOverMessage("Expedition error", "It was impossible to get the current expedition type. Expeditions are now disabled!", "siren");

                                    BHBot.browser.readScreen();
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                    if (seg != null) BHBot.browser.clickOnSeg(seg);
                                    BHBot.browser.readScreen(2 * SECOND);
                                    continue;
                                }

                                String portalName = getExpeditionPortalName(currentExpedition, targetPortal);
                                BHBot.logger.info("Attempting " + portalName + " Portal at difficulty " + targetDifficulty);

                                //write current portal and difficulty to global values for difficultyFailsafe
                                expeditionFailsafePortal = targetPortal;
                                expeditionFailsafeDifficulty = targetDifficulty;

                                // click on the chosen portal:
                                Point p = getExpeditionIconPos(currentExpedition, targetPortal);
                                if (p == null) {
                                    BHBot.settings.activitiesEnabled.remove("e");
                                    BHBot.logger.error("It was impossible to get portal position for " + portalName + ". Expeditions are now disabled!");
                                    if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors)
                                        sendPushOverMessage("Expedition error", "It was impossible to get portal position for " + portalName + ". Expeditions are now disabled!", "siren");

                                    BHBot.browser.readScreen();
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
                                    if (seg != null) BHBot.browser.clickOnSeg(seg);
                                    BHBot.browser.readScreen(2 * SECOND);
                                    continue;
                                }

                                BHBot.browser.clickInGame(p.x, p.y);

                                // select difficulty if needed:
                                int difficulty = detectDifficulty(BrowserManager.cues.get("DifficultyExpedition"));
                                if (difficulty == 0) { // error!
                                    BHBot.logger.warn("Due to an error in difficulty detection, Expedition will be skipped.");
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                    while (seg != null) {
                                        BHBot.browser.clickOnSeg(seg);
                                        BHBot.browser.readScreen(2 * SECOND);
                                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                    }
                                    continue;
                                }

                                if (difficulty != targetDifficulty) {
                                    BHBot.logger.info("Detected Expedition difficulty level: " + difficulty + ", settings level is " + targetDifficulty + ". Changing..");
                                    boolean result = selectDifficulty(difficulty, targetDifficulty, BrowserManager.cues.get("SelectDifficultyExpedition"), 5);
                                    if (!result) { // error!
                                        // see if drop down menu is still open and close it:
                                        BHBot.browser.readScreen();
                                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                        while (seg != null) {
                                            BHBot.browser.clickOnSeg(seg);
                                            BHBot.browser.readScreen(2 * SECOND);
                                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                                        }
                                        BHBot.logger.error("Due to an error in difficulty selection, Expedition will be skipped.");
                                        continue;
                                    }
                                }

                                //click enter
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Enter"), 2 * SECOND, BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);

                                //click enter
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), 3 * SECOND, BHBot.browser);
                                if (seg != null) {
                                    BHBot.browser.clickOnSeg(seg);
                                } else {
                                    BHBot.logger.error("No accept button for expedition team!");
                                    saveGameScreen("expedtion-no-accept", "errors");
                                    restart();
                                }

                                if (handleTeamMalformedWarning()) {
                                    BHBot.logger.error("Team incomplete, doing emergency restart..");
                                    restart();
                                    continue;
                                } else {
                                    state = State.Expedition;
                                    BHBot.logger.info(portalName + " portal initiated!");
                                    autoShrined = false;
                                    autoBossRuned = false;
                                }

                                if (handleGuildLeaveConfirm()) {
                                    restart();
                                    continue;
                                }
                            }
                            continue;
                        } else {
                            // do neither gvg nor invasion
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
                            BHBot.browser.clickOnSeg(seg);
                            Misc.sleep(2 * SECOND);
                            continue;
                        }
                    } // badges

                    // Check worldBoss:
                    if ("w".equals(currentActivity)) {
                        timeLastEnergyCheck = Misc.getTime();
                        int energy = getEnergy();
                        globalEnergy = energy;
                        BHBot.logger.readout("Energy: " + energy + "%, required: >" + BHBot.settings.minEnergyPercentage + "%");

                        if (energy == -1) { // error
                            if (BHBot.scheduler.doWorldBossImmediately)
                                BHBot.scheduler.doWorldBossImmediately = false; // reset it
                            BHBot.scheduler.restoreIdleTime();


                            continue;
                        }

                        if (!BHBot.scheduler.doWorldBossImmediately && (energy <= BHBot.settings.minEnergyPercentage)) {

                            //if we have 1 resource and need 5 we don't need to check every 10 minutes, this increases the timer so we start checking again when we are one under the check limit
                            int energyDifference = BHBot.settings.minEnergyPercentage - energy; //difference between needed and current resource
                            if (energyDifference > 1) {
                                int increase = (energyDifference - 1) * 4;
                                ENERGY_CHECK_INTERVAL = increase * MINUTE; //add 4 minutes to the check interval for each energy % needed above 1
                            } else ENERGY_CHECK_INTERVAL = 10 * MINUTE; //if we only need 1 check every 10 minutes

                            Misc.sleep(SECOND);
                            continue;
                        } else {
                            // do the WorldBoss!
                            if (BHBot.scheduler.doWorldBossImmediately)
                                BHBot.scheduler.doWorldBossImmediately = false; // reset it

                            if (!checkWorldBossInput()) {
                                BHBot.logger.warn("Invalid world boss settings detected, World Boss will be skipped");
                                continue;
                            }

                            //configure activity runes
                            handleMinorRunes("w");

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("WorldBoss"), BHBot.browser);
                            if (seg != null) {
                                BHBot.browser.clickOnSeg(seg);
                            } else {
                                BHBot.logger.error("World Boss button not found");
                                continue;
                            }

                            BHBot.browser.readScreen();
                            detectCharacterDialogAndHandleIt(); //clear dialogue

                            WorldBoss wbType = WorldBoss.fromLetter(BHBot.settings.worldBossSettings.get(0));
                            if (wbType == null) {
                                BHBot.logger.error("Unkwon World Boss type: " + BHBot.settings.worldBossSettings.get(0) + ". Disabling World Boss");
                                BHBot.settings.activitiesEnabled.remove("w");
                                restart();
                                continue;
                            }

                            int worldBossDifficulty = Integer.parseInt(BHBot.settings.worldBossSettings.get(1));
                            int worldBossTier = Integer.parseInt(BHBot.settings.worldBossSettings.get(2));
                            int worldBossTimer = BHBot.settings.worldBossTimer;

                            //new settings loading
                            String worldBossDifficultyText = worldBossDifficulty == 1 ? "Normal" : worldBossDifficulty == 2 ? "Hard" : "Heroic";

                            if (!BHBot.settings.worldBossSolo) {
                                BHBot.logger.info("Attempting " + worldBossDifficultyText + " T" + worldBossTier + " " + wbType.getName() + ". Lobby timeout is " + worldBossTimer + "s.");
                            } else {
                                BHBot.logger.info("Attempting " + worldBossDifficultyText + " T" + worldBossTier + " " + wbType.getName() + " Solo");
                            }

                            BHBot.browser.readScreen();
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("BlueSummon"), SECOND, BHBot.browser);
                            if (seg != null) {
                                BHBot.browser.clickOnSeg(seg);
                            } else {
                                BHBot.logger.error("Impossible to find blue summon in world boss.");

                                String WBErrorScreen = saveGameScreen("wb-no-blue-summon", "errors");
                                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                                    if (WBErrorScreen != null) {
                                        sendPushOverMessage("World Boss error", "Impossible to find blue summon.", "siren", MessagePriority.NORMAL, new File(WBErrorScreen));
                                    } else {
                                        sendPushOverMessage("World Boss error", "Impossible to find blue summon.", "siren", MessagePriority.NORMAL, null);
                                    }
                                }

                                closePopupSecurely(BrowserManager.cues.get("WorldBossTitle"), BrowserManager.cues.get("X"));
                                continue;
                            }
                            BHBot.browser.readScreen(2 * SECOND); //wait for screen to stablise

                            //world boss type selection
                            if (!handleWorldBossSelection(wbType)) {
                                BHBot.logger.error("Impossible to change select the desired World Boss. Restarting...");
                                restart();
                                continue;
                            }

//							Misc.sleep(SECOND); //more stabilising if we changed world boss type
                            BHBot.browser.readScreen(SECOND);
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("LargeGreenSummon"), 2 * SECOND, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg); //selected world boss

                            BHBot.browser.readScreen(SECOND);
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Private"), SECOND, BHBot.browser);
                            if (!BHBot.settings.worldBossSolo) {
                                if (seg != null) {
                                    BHBot.logger.info("Unchecking private lobby");
                                    BHBot.browser.clickOnSeg(seg);
                                }
                            } else {
                                if (seg == null) {
                                    BHBot.logger.info("Enabling private lobby for solo World Boss");
                                    Misc.sleep(500);
                                    BHBot.browser.clickInGame(340, 350);
                                    BHBot.browser.readScreen(500);
                                }
                            }

                            //world boss tier selection

                            int currentTier = detectWorldBossTier();
                            Misc.sleep(500);
                            if (currentTier != worldBossTier) {
                                BHBot.logger.info("T" + currentTier + " detected, changing to T" + worldBossTier);
                                Misc.sleep(500);
                                if (!changeWorldBossTier(worldBossTier)) {
                                    restart();
                                    continue;
                                }
                            }

                            //world boss difficulty selection

                            int currentDifficulty = detectWorldBossDifficulty();
                            String currentDifficultyName = (currentDifficulty == 1 ? "Normal" : currentDifficulty == 2 ? "Hard" : "Heroic");
                            String settingsDifficultyName = (worldBossDifficulty == 1 ? "Normal" : worldBossDifficulty == 2 ? "Hard" : "Heroic");
                            if (currentDifficulty != worldBossDifficulty) {
                                BHBot.logger.info(currentDifficultyName + " detected, changing to " + settingsDifficultyName);
                                changeWorldBossDifficulty(worldBossDifficulty);
                            }

                            BHBot.browser.readScreen(SECOND);
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("SmallGreenSummon"), SECOND * 2, BHBot.browser);
                            BHBot.browser.clickOnSeg(seg); //accept current settings

                            boolean insufficientEnergy = handleNotEnoughEnergyPopup(SECOND * 3, State.WorldBoss);
                            if (insufficientEnergy) {
                                continue;
                            }

                            BHBot.logger.info("Starting lobby");

                            /*
                             *
                             * this part gets messy as WB is much more dynamic and harder to automate with human players
                             * I've tried to introduce as many error catchers with restarts(); as possible to keep things running smoothly
                             *
                             */

                            //wait for lobby to fill with a timer
                            if (!BHBot.settings.worldBossSolo) {
                                Bounds inviteButton = inviteBounds(wbType.getLetter());
                                for (int i = 0; i < worldBossTimer; i++) {
                                    BHBot.browser.readScreen(SECOND);
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("Invite"), 0, inviteButton, BHBot.browser);
                                    if (seg != null) { //while the relevant invite button exists
                                        if (i != 0 && (i % 15) == 0) { //every 15 seconds
                                            int timeLeft = worldBossTimer - i;
                                            BHBot.logger.info("Waiting for full team. Time out in " + timeLeft + " seconds.");
                                        }
                                        if (i == (worldBossTimer - 1)) { //out of time
                                            if (BHBot.settings.dungeonOnTimeout) { //setting to run a dungeon if we cant fill a lobby
                                                BHBot.logger.info("Lobby timed out, running dungeon instead");
                                                closeWorldBoss();
                                                Misc.sleep(4 * SECOND); //make sure we're stable on the main screen
                                                BHBot.scheduler.doDungeonImmediately = true;
                                            } else {
                                                BHBot.logger.info("Lobby timed out, returning to main screen.");
                                                // we say we checked (interval - 1) minutes ago, so we check again in a minute
                                                timeLastEnergyCheck = Misc.getTime() - ((ENERGY_CHECK_INTERVAL) - MINUTE);
                                                closeWorldBoss();
                                            }
                                        }
                                    } else {
                                        BHBot.logger.info("Lobby filled in " + i + " seconds!");
                                        i = worldBossTimer; // end the for loop

                                        //check that all players are ready
                                        BHBot.logger.info("Making sure everyones ready..");
                                        int j = 1;
                                        while (j != 20) { //ready check for 10 seconds
                                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Unready"), 2 * SECOND, BHBot.browser); //this checks all 4 ready statuses
                                            BHBot.browser.readScreen();
                                            if (seg == null) {// no red X's found
                                                break;
                                            } else { //red X's found
                                                //BHBot.logger.info(Integer.toString(j));
                                                j++;
                                                Misc.sleep(500); //check every 500ms
                                            }
                                        }

                                        if (j >= 20) {
                                            BHBot.logger.error("Ready check not passed after 10 seconds, restarting");
                                            restart();
                                        }

                                        Misc.sleep(500);
                                        BHBot.browser.readScreen();
                                        MarvinSegment segStart = MarvinSegment.fromCue(BrowserManager.cues.get("Start"), 5 * SECOND, BHBot.browser);
                                        if (segStart != null) {
                                            BHBot.browser.clickOnSeg(segStart); //start World Boss
                                            BHBot.browser.readScreen();
                                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("TeamNotFull"), 2 * SECOND, BHBot.browser); //check if we have the team not full screen an clear it
                                            if (seg != null) {
                                                Misc.sleep(2 * SECOND); //wait for animation to finish
                                                BHBot.browser.clickInGame(330, 360); //yesgreen cue has issues so we use XY to click on Yes
                                            }
                                            BHBot.logger.info(worldBossDifficultyText + " T" + worldBossTier + " " + wbType.getName() + " started!");
                                            state = State.WorldBoss;
                                        } else { //generic error / unknown action restart
                                            BHBot.logger.error("Something went wrong while attempting to start the World Boss, restarting");
                                            saveGameScreen("wb-no-start-button", "errors");
                                            restart();
                                        }

                                    }
                                }
                            } else {
                                BHBot.browser.readScreen();
                                MarvinSegment segStart = MarvinSegment.fromCue(BrowserManager.cues.get("Start"), 2 * SECOND, BHBot.browser);
                                if (segStart != null) {
                                    BHBot.browser.clickOnSeg(segStart); //start World Boss
                                    Misc.sleep(2 * SECOND); //wait for dropdown animation to finish
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 2 * SECOND, BHBot.browser); //clear empty team prompt
                                    //click anyway this cue has issues
                                    if (seg == null) {
                                        Misc.sleep(500);
                                    } else {
                                        BHBot.browser.clickOnSeg(seg);
                                    }
                                    BHBot.browser.clickInGame(330, 360); //yesgreen cue has issues so we use pos to click on Yes as a backup
                                    BHBot.logger.info(worldBossDifficultyText + " T" + worldBossTier + " " + wbType.getName() + " Solo started!");
                                    state = State.WorldBoss;
                                    continue;
                                }
                                continue;
                            }
                        }
                        continue;
                    } // World Boss

                    //bounties activity
                    if ("b".equals(currentActivity)) {
                        timeLastBountyCheck = Misc.getTime();

                        if (BHBot.scheduler.collectBountiesImmediately) {
                            BHBot.scheduler.collectBountiesImmediately = false; //disable collectImmediately again if its been activated
                        }
                        BHBot.logger.info("Checking for completed bounties");

                        BHBot.browser.clickInGame(130, 440);

                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Bounties"), SECOND * 5, BHBot.browser);
                        if (seg != null) {
                            BHBot.browser.readScreen();
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Loot"), SECOND * 5, new Bounds(505, 245, 585, 275), BHBot.browser);
                            while (seg != null) {
                                BHBot.browser.clickOnSeg(seg);
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("WeeklyRewards"), SECOND * 5, new Bounds(190, 100, 615, 400), BHBot.browser);
                                if (seg != null) {
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                                    if (seg != null) {
                                        if ((BHBot.settings.screenshots.contains("b"))) {
                                            saveGameScreen("bounty-loot", "rewards");
                                        }
                                        BHBot.browser.clickOnSeg(seg);
                                        BHBot.logger.info("Collected bounties");
                                        Misc.sleep(SECOND * 2);
                                    } else {
                                        BHBot.logger.error("Error when collecting bounty items, restarting...");
                                        saveGameScreen("bounties-error-collect", "errors");
                                        restart();
                                    }
                                } else {
                                    BHBot.logger.error("Error finding bounty item dialog, restarting...");
                                    saveGameScreen("bounties-error-item");
                                    restart();
                                }

                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Loot"), SECOND * 5, new Bounds(505, 245, 585, 275), BHBot.browser);
                            }

                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                            if (seg != null) {
                                BHBot.browser.clickOnSeg(seg);
                            } else {
                                BHBot.logger.error("Impossible to close the bounties dialog, restarting...");
                                saveGameScreen("bounties-error-closing");
                                restart();
                            }
                        } else {
                            BHBot.logger.error("Impossible to detect the Bounties dialog, restarting...");
                            saveGameScreen("bounties-error-dialog");
                            restart();
                        }
                        BHBot.browser.readScreen(SECOND * 2);
                        continue;
                    }

                    //fishing baits
                    if ("a".equals(currentActivity)) {
                        timeLastFishingBaitsCheck = Misc.getTime();

                        if (BHBot.scheduler.doFishingBaitsImmediately) {
                            BHBot.scheduler.doFishingBaitsImmediately = false; //disable collectImmediately again if its been activated
                        }

                        handleFishingBaits();
                        continue;
                    }

                    //fishing
                    if ("f".equals(currentActivity)) {
                        timeLastFishingCheck = Misc.getTime();

                        if (BHBot.scheduler.doFishingImmediately) {
                            BHBot.scheduler.doFishingImmediately = false; //disable collectImmediately again if its been activated
                        }

                        if ((Misc.getTime() - timeLastFishingBaitsCheck) > DAY) { //if we haven't collected bait today we need to do that first
                            handleFishingBaits();
                        }

                        boolean botPresent = new File("bh-fisher.jar").exists();
                        if (!botPresent) {
                            BHBot.logger.warn("bh-fisher.jar not found in root directory, fishing disabled.");
                            BHBot.logger.warn("For information on configuring fishing check the wiki page on github");
                            BHBot.settings.activitiesEnabled.remove("f");
                            return;
                        } else {
                            handleFishing();
                        }
                        continue;
                    }

                } // main screen processing
            } catch (Exception e) {
                if (e instanceof org.openqa.selenium.WebDriverException && e.getMessage().startsWith("chrome not reachable")) {
                    // this happens when user manually closes the Chrome window, for example
                    BHBot.logger.error("Error: chrome is not reachable! Restarting...", e);
                    restart();
                    continue;
                } else if (e instanceof java.awt.image.RasterFormatException) {
                    // not sure in what cases this happen, but it happens
                    BHBot.logger.error("Error: RasterFormatException. Attempting to re-align the window...", e);
                    Misc.sleep(500);
                    BHBot.browser.scrollGameIntoView();
                    Misc.sleep(500);
                    try {
                        BHBot.browser.readScreen();
                    } catch (Exception e2) {
                        BHBot.logger.error("Error: re-alignment failed(" + e2.getMessage() + "). Restarting...");
                        restart();
                        continue;
                    }
                    BHBot.logger.info("Realignment seems to have worked.");
                    continue;
                } else if (e instanceof org.openqa.selenium.StaleElementReferenceException) {
                    // this is a rare error, however it happens. See this for more info:
                    // http://www.seleniumhq.org/exceptions/stale_element_reference.jsp
                    BHBot.logger.error("Error: StaleElementReferenceException. Restarting...", e);
                    restart();
                    continue;
                } else if (e instanceof com.assertthat.selenium_shutterbug.utils.web.ElementOutsideViewportException) {
                    BHBot.logger.info("Error: ElementOutsideViewportException. Ignoring...");
                    //added this 1 second delay as attempting ads often triggers this
                    //will trigger the restart in the if statement below after 30 seconds
                    Misc.sleep(SECOND);
                    // we must not call 'continue' here, because this error could be a loop error, this is why we need to increase numConsecutiveException bellow in the code!
                } else if (e instanceof org.openqa.selenium.TimeoutException) {
                    /* When we get time out errors it may be possible that the BHBot.browser has crashed so it is impossible to take screenshots
                     * For this reason we do a standard restart.
                     */
                    restart(false);
                    continue;
                } else {
                    // unknown error!
                    BHBot.logger.error("Unmanaged exception in main run loop", e);
                    restart();
                }

                numConsecutiveException++;
                if (numConsecutiveException > MAX_CONSECUTIVE_EXCEPTIONS) {
                    numConsecutiveException = 0; // reset it
                    BHBot.logger.warn("Problem detected: number of consecutive exceptions is higher than " + MAX_CONSECUTIVE_EXCEPTIONS + ". This probably means we're caught in a loop. Restarting...");
                    restart();
                    continue;
                }

                BHBot.scheduler.restoreIdleTime();

                continue;
            }


            // well, we got through all the checks. Means that nothing much has happened. So lets sleep for a second in order to not make processing too heavy...
            numConsecutiveException = 0; // reset exception counter
            BHBot.scheduler.restoreIdleTime(); // revert changes to idle time
            if (BHBot.finished) break; // skip sleeping if finished flag has been set!
            Misc.sleep(SECOND);
        } // main while loop

        BHBot.logger.info("Stopping main thread...");
        BHBot.browser.close();
        BHBot.logger.info("Main thread stopped.");
    }

    private Bounds inviteBounds(String wb) {
        Bounds inviteButton;
        switch (wb) {
            // 3rd Invite button for Nether, 3xt3rmin4tion & Brimstone
            case "m":
                inviteButton = new Bounds(330, 330, 460, 380); // 4th Invite button for Melvin
                break;
            case "o":
                inviteButton = new Bounds(336, 387, 452, 422); // 5th Invite button for Orlag
                break;
            case "n":
            case "3":
            case "b":
            default:
                inviteButton = new Bounds(334, 275, 456, 323); // on error return 3rd invite
                break;
        }
        return inviteButton;
    }

    private String activitySelector() {

        if (BHBot.scheduler.doRaidImmediately) {
            return "r";
        } else if (BHBot.scheduler.doDungeonImmediately) {
            return "d";
        } else if (BHBot.scheduler.doWorldBossImmediately) {
            return "w";
        } else if (BHBot.scheduler.doTrialsImmediately) {
            return "t";
        } else if (BHBot.scheduler.doGauntletImmediately) {
            return "g";
        } else if (BHBot.scheduler.doPVPImmediately) {
            return "p";
        } else if (BHBot.scheduler.doInvasionImmediately) {
            return "i";
        } else if (BHBot.scheduler.doGVGImmediately) {
            return "v";
        } else if (BHBot.scheduler.doExpeditionImmediately) {
            return "e";
        } else if (BHBot.scheduler.collectBountiesImmediately) {
            return "b";
        } else if (BHBot.scheduler.doFishingBaitsImmediately) {
            return "a";
        } else if (BHBot.scheduler.doFishingImmediately) {
            return "f";
        }

        //return null if no matches
        if (!BHBot.settings.activitiesEnabled.isEmpty()) {

            String activity;

            if (!BHBot.settings.activitiesRoundRobin) {
                activitysIterator = BHBot.settings.activitiesEnabled.iterator(); //reset the iterator
            }

            //loop through in defined order, if we match activity and timer we select the activity
            while (activitysIterator.hasNext()) {

                try {
                    activity = activitysIterator.next(); //set iterator to string for .equals()
                } catch (ConcurrentModificationException e) {
                    activitysIterator = BHBot.settings.activitiesEnabled.iterator();
                    activity = activitysIterator.next();
                }

                if (activity.equals("r") && ((Misc.getTime() - timeLastShardsCheck) > (long) (15 * MINUTE))) {
                    return "r";
                } else if ("d".equals(activity) && ((Misc.getTime() - timeLastEnergyCheck) > ENERGY_CHECK_INTERVAL)) {
                    return "d";
                } else if ("w".equals(activity) && ((Misc.getTime() - timeLastEnergyCheck) > ENERGY_CHECK_INTERVAL)) {
                    return "w";
                } else if ("t".equals(activity) && ((Misc.getTime() - timeLastTrialsTokensCheck) > TOKENS_CHECK_INTERVAL)) {
                    return "t";
                } else if ("g".equals(activity) && ((Misc.getTime() - timeLastGauntletTokensCheck) > TOKENS_CHECK_INTERVAL)) {
                    return "g";
                } else if ("p".equals(activity) && ((Misc.getTime() - timeLastTicketsCheck) > TICKETS_CHECK_INTERVAL)) {
                    return "p";
                } else if ("i".equals(activity) && ((Misc.getTime() - timeLastInvBadgesCheck) > BADGES_CHECK_INTERVAL)) {
                    return "i";
                } else if ("v".equals(activity) && ((Misc.getTime() - timeLastGVGBadgesCheck) > BADGES_CHECK_INTERVAL)) {
                    return "v";
                } else if ("e".equals(activity) && ((Misc.getTime() - timeLastExpBadgesCheck) > BADGES_CHECK_INTERVAL)) {
                    return "e";
                } else if ("b".equals(activity) && ((Misc.getTime() - timeLastBountyCheck) > (long) HOUR)) {
                    return "b";
                } else if ("a".equals(activity) && ((Misc.getTime() - timeLastFishingBaitsCheck) > (long) DAY)) {
                    return "a";
                } else if ("f".equals(activity) && ((Misc.getTime() - timeLastFishingCheck) > (long) DAY)) {
                    return "f";
                }
            }

            // If we reach this point activityIterator.hasNext() is false
            if (BHBot.settings.activitiesRoundRobin) {
                activitysIterator = BHBot.settings.activitiesEnabled.iterator();
            }

        }
        return null;
    }

    private boolean openSettings(@SuppressWarnings("SameParameterValue") int delay) {
        BHBot.browser.readScreen();

        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("SettingsGear"), BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(delay);
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Settings"), SECOND * 3, BHBot.browser);
            return seg != null;
        } else {
            BHBot.logger.error("Impossible to find the settings button!");
            saveGameScreen("open-settings-no-btn", "errors");
            return false;
        }
    }

    boolean checkShrineSettings(boolean ignoreBoss, boolean ignoreShrines) {
        //open settings
        int ignoreBossCnt = 0;
        int ignoreShrineCnt = 0;

        if (openSettings(SECOND)) {
            if (ignoreBoss) {
                while (MarvinSegment.fromCue(BrowserManager.cues.get("IgnoreBoss"), SECOND, BHBot.browser) != null) {
                    BHBot.logger.debug("Enabling Ignore Boss");
                    BHBot.browser.clickInGame(194, 366);
                    BHBot.browser.readScreen(500);

                    if (ignoreBossCnt++ > 10) {
                        BHBot.logger.error("Impossible to enable Ignore Boss");
                        return false;
                    }
                }
                ignoreBossSetting = true;
                BHBot.logger.debug("Ignore Boss Enabled");
            } else {
                while (MarvinSegment.fromCue(BrowserManager.cues.get("IgnoreBoss"), SECOND, BHBot.browser) == null) {
                    BHBot.logger.debug("Disabling Ignore Boss");
                    BHBot.browser.clickInGame(194, 366);
                    BHBot.browser.readScreen(500);

                    if (ignoreBossCnt++ > 10) {
                        BHBot.logger.error("Impossible to Disable Ignore Boss");
                        return false;
                    }
                }
                ignoreBossSetting = false;
                BHBot.logger.debug("Ignore Boss Disabled");
            }

            if (ignoreShrines) {
                while (MarvinSegment.fromCue(BrowserManager.cues.get("IgnoreShrines"), SECOND, BHBot.browser) != null) {
                    BHBot.logger.debug("Enabling Ignore Shrine");
                    BHBot.browser.clickInGame(194, 402);
                    BHBot.browser.readScreen(500);

                    if (ignoreShrineCnt++ > 10) {
                        BHBot.logger.error("Impossible to enable Ignore Shrines");
                        return false;
                    }
                }
                ignoreShrinesSetting = true;
                BHBot.logger.debug("Ignore Shrine Enabled");
            } else {
                while (MarvinSegment.fromCue(BrowserManager.cues.get("IgnoreShrines"), SECOND, BHBot.browser) == null) {
                    BHBot.logger.debug("Disabling Ignore Shrine");
                    BHBot.browser.clickInGame(194, 402);
                    BHBot.browser.readScreen(500);

                    if (ignoreShrineCnt++ > 10) {
                        BHBot.logger.error("Impossible to disable Ignore Shrines");
                        return false;
                    }
                }
                ignoreShrinesSetting = false;
                BHBot.logger.debug("Ignore Shrine Disabled");
            }

            BHBot.browser.readScreen(SECOND);

            closePopupSecurely(BrowserManager.cues.get("Settings"), new Cue(BrowserManager.cues.get("X"), new Bounds(608, 39, 711, 131)));

            return true;
        } else {
            BHBot.logger.warn("Impossible to open settings menu!");
            return false;
        }
    }

    private boolean openRunesMenu() {
        // Open character menu
        BHBot.browser.clickInGame(55, 465);

        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("Runes"), 15 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.warn("Error: unable to detect runes button! Skipping...");
            return true;
        }

        BHBot.browser.clickOnSeg(seg);
        Misc.sleep(500); //sleep for window animation (15s below was crashing the bot, not sure why

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("RunesLayout"), 15 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.warn("Error: unable to detect rune layout! Skipping...");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
            }
            return true;
        }

        return false;
    }

    boolean detectEquippedMinorRunes(boolean enterRunesMenu, boolean exitRunesMenu) {

        if (enterRunesMenu && openRunesMenu())
            return false;

        // determine equipped runes
        leftMinorRune = null;
        rightMinorRune = null;
        BHBot.browser.readScreen();
        for (MinorRune rune : MinorRune.values()) {
            Cue runeCue = rune.getRuneCue();

            // left rune
            MarvinSegment seg = MarvinSegment.fromCue(runeCue, 0, new Bounds(230, 245, 320, 330), BHBot.browser);
            if (seg != null)
                leftMinorRune = rune;

            // right rune
            seg = MarvinSegment.fromCue(runeCue, 0, new Bounds(480, 245, 565, 330), BHBot.browser);
            if (seg != null)
                rightMinorRune = rune;

        }

        if (exitRunesMenu) {
            Misc.sleep(500);
            closePopupSecurely(BrowserManager.cues.get("RunesLayout"), BrowserManager.cues.get("X"));
            Misc.sleep(500);
            closePopupSecurely(BrowserManager.cues.get("StripSelectorButton"), BrowserManager.cues.get("X"));
        }

        boolean success = true;
        if (leftMinorRune == null) {
            BHBot.logger.warn("Error: Unable to detect left minor rune!");
            success = false;
        } else {
            BHBot.logger.debug(leftMinorRune + " equipped in left slot.");
        }
        if (rightMinorRune == null) {
            BHBot.logger.warn("Error: Unable to detect right minor rune!");
            success = false;
        } else {
            BHBot.logger.debug(rightMinorRune + " equipped in right slot.");
        }

        return success;
    }
    

    /**
     * This will handle dialog that open up when you encounter a boss for the first time, for example, or open a raid window or trials window for the first time, etc.
     */
    private void detectCharacterDialogAndHandleIt() {
        MarvinSegment right;
        MarvinSegment left;
        int steps = 0;

        while (true) {
            BHBot.browser.readScreen();

            right = MarvinSegment.fromCue(BrowserManager.cues.get("DialogRight"), BHBot.browser);
            left = MarvinSegment.fromCue(BrowserManager.cues.get("DialogLeft"), BHBot.browser);

            //if we don't find either exit
            if (left == null && right == null) break;

            // if we find left or right click them
            if (left != null) BHBot.browser.clickOnSeg(left);
            if (right != null) BHBot.browser.clickOnSeg(right);

            steps++;
            Misc.sleep(SECOND);
        }

        if (steps > 0)
            BHBot.logger.info("Character dialog dismissed.");
    }

    /**
     *  Returns the position of the detected cue as Bounds
     */

    private Bounds getSegBounds(MarvinSegment seg) {
        return new Bounds(seg.x1, seg.y1, seg.x2, seg.y2);
    }

    /**
     * Returns amount of energy in percent (0-100). Returns -1 in case it cannot read energy for some reason.
     */
    private int getEnergy() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("EnergyBar"), BHBot.browser);

        if (seg == null) // this should probably not happen
            return -1;

        int left = seg.x2 + 1;
        int top = seg.y1 + 6;

        final Color full = new Color(136, 197, 44);
        //final Color limit = new Color(87, 133, 21);
        //final Color empty = new Color(49, 50, 51);

        int value = 0;

        // energy bar is 80 pixels long (however last two pixels will have "medium" color and not full color (it's so due to shading))
        for (int i = 0; i < 78; i++) {
            value = i;
            Color col = new Color(BHBot.browser.getImg().getRGB(left + i, top));

            if (!col.equals(full))
                break;
        }

        return Math.round(value * (100 / 77.0f)); // scale it to interval [0..100]
    }

    /**
     * Returns number of tickets left (for PvP) in interval [0..10]. Returns -1 in case it cannot read number of tickets for some reason.
     */
    private int getTickets() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("TicketBar"), BHBot.browser);

        if (seg == null) // this should probably not happen
            return -1;

        int left = seg.x2 + 1;
        int top = seg.y1 + 6;

        final Color full = new Color(226, 42, 81);

        int value = 0;
        int maxTickets = BHBot.settings.maxTickets;

        // ticket bar is 80 pixels long (however last two pixels will have "medium" color and not full color (it's so due to shading))
        for (int i = 0; i < 78; i++) {
            value = i;
            Color col = new Color(BHBot.browser.getImg().getRGB(left + i, top));

            if (!col.equals(full))
                break;
        }

        value = value + 2; //add the last 2 pixels to get an accurate count
        return Math.round(value * (maxTickets / 77.0f)); // scale it to interval [0..10]
    }

    /**
     * Returns number of shards that we have. Works only if raid popup is open. Returns -1 in case it cannot read number of shards for some reason.
     */
    private int getShards() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("RaidPopup"), BHBot.browser);

        if (seg == null) // this should probably not happen
            return -1;

        int left = seg.x2 + 1;
        int top = seg.y1 + 9;

        final Color full = new Color(199, 79, 175);

        int value = 0;
        int maxShards = BHBot.settings.maxShards;

        for (int i = 0; i < 76; i++) {
            value = i;
            Color col = new Color(BHBot.browser.getImg().getRGB(left + i, top));

            if (!col.equals(full))
                break;
        }

        value = value + 2; //add the last 2 pixels to get an accurate count
        return Math.round(value * (maxShards / 75.0f)); // round to nearest whole number
    }

    /**
     * Returns number of tokens we have. Works only if trials/gauntlet window is open. Returns -1 in case it cannot read number of tokens for some reason.
     */
    private int getTokens() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("TokenBar"), BHBot.browser);

        if (seg == null) // this should probably not happen
            return -1;

        int left = seg.x2;
        int top = seg.y1 + 6;

        final Color full = new Color(17, 208, 226);

        int value = 0;
        int maxTokens = BHBot.settings.maxTokens;

        // tokens bar is 78 pixels wide (however last two pixels will have "medium" color and not full color (it's so due to shading))
        for (int i = 0; i < 76; i++) {
            value = i + 1;
            Color col = new Color(BHBot.browser.getImg().getRGB(left + i, top));

            if (!col.equals(full))
                break;
        }

        return Math.round(value * (maxTokens / 76.0f)); // scale it to interval [0..10]
    }

    /**
     * Returns number of badges we have. Works only if GVG window is open. Returns -1 in case it cannot read number of badges for some reason.
     */
    private int getBadges() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("BadgeBar"), BHBot.browser);

        if (seg == null) // this should probably not happen
            return -1;

        int left = seg.x2 + 1;
        int top = seg.y1 + 6;

        final Color full = new Color(17, 208, 226);

        int value = 0;
        int maxBadges = BHBot.settings.maxBadges;

        // badges bar is 78 pixels wide (however last two pixels will have "medium" color and not full color (it's so due to shading))
        for (int i = 0; i < 76; i++) {
            value = i;
            Color col = new Color(BHBot.browser.getImg().getRGB(left + i, top));

            if (!col.equals(full))
                break;
        }

        value = value + 2; //add the last 2 pixels to get an accurate count
        return Math.round(value * (maxBadges / 75.0f)); // scale it to interval [0..10]
    }

    /**
     * Processes any kind of dungeon: <br>
     * - normal dungeon <br>
     * - raid <br>
     * - trial <br>
     * - gauntlet <br>
     */
    private void processDungeon() {
        MarvinSegment seg;
        BHBot.browser.readScreen();

        if (!startTimeCheck) {
            activityStartTime = TimeUnit.MILLISECONDS.toSeconds(Misc.getTime());
            BHBot.logger.debug("Start time: " + activityStartTime);
            outOfEncounterTimestamp = TimeUnit.MILLISECONDS.toSeconds(Misc.getTime());
            inEncounterTimestamp = TimeUnit.MILLISECONDS.toSeconds(Misc.getTime());
            startTimeCheck = true;
            encounterStatus = false; //true is in encounter, false is out of encounter
            speedChecked = false;
        }

        long activityDuration = (TimeUnit.MILLISECONDS.toSeconds(Misc.getTime()) - activityStartTime);

        /*
         * Encounter detection code
         * We use guild button visibility to detect whether we are in combat
         */
        MarvinSegment guildButtonSeg = MarvinSegment.fromCue(BrowserManager.cues.get("GuildButton"), BHBot.browser);
        if (guildButtonSeg != null) {
            outOfEncounterTimestamp = TimeUnit.MILLISECONDS.toSeconds(Misc.getTime());
            if (encounterStatus) {
                BHBot.logger.trace("Updating idle time (Out of combat)");
                BHBot.scheduler.resetIdleTime(true);
                encounterStatus = false;
            }
        } else {
            inEncounterTimestamp = TimeUnit.MILLISECONDS.toSeconds(Misc.getTime());
            if (!encounterStatus) {
                BHBot.logger.trace("Updating idle time (In combat)");
                BHBot.scheduler.resetIdleTime(true);
                encounterStatus = true;
            }
        }

        /*
         *  handleLoot code
         *  It's enabled in these activities to try and catch real-time loot drops, as the loot window automatically closes
         */
        if (state == State.Raid || state == State.Dungeon || state == State.Expedition || state == State.Trials) {
            handleLoot();
        }

        /*
         * autoRune Code
         */
        if (BHBot.settings.autoBossRune.containsKey(state.getShortcut()) && !encounterStatus) {
            handleAutoBossRune();
        }

        /*
         * autoShrine Code
         */
        if (BHBot.settings.autoShrine.contains(state.getShortcut()) && !encounterStatus) {
            handleAutoShrine();
        }

        /*
         * autoRevive code
         * This also handles re-enabling auto
         */
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), BHBot.browser);
        if (seg != null) {
            handleAutoRevive();
        }

        /*
         * autoBribe/Persuasion code
         */
        if ((state == State.Raid || state == State.Dungeon  || state == State.UnidentifiedDungeon) && (activityDuration % 5 == 0) && encounterStatus) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Persuade"), BHBot.browser);
            if (seg != null) {
                handleFamiliarEncounter();
            }
        }

        /*
         *  Skeleton key code
         *  encounterStatus is set to true as the window obscures the guild icon
         */
        if (activityDuration % 5 == 0 && encounterStatus) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("SkeletonTreasure"), BHBot.browser);
            if (seg != null) {
                if (handleSkeletonKey()) {
                    restart();
                }
            }
        }

        /*
         *  1x Speed check
         *  We check once per activity, when we're in combat
         */
        if (!speedChecked && encounterStatus) { //we check once per activity when we are in encounter
            MarvinSegment speedFull = MarvinSegment.fromCue("Speed_Full", BHBot.browser);
            MarvinSegment speedLabel = MarvinSegment.fromCue("Speed", BHBot.browser);
            if (speedLabel != null && speedFull == null) { //if we see speed label but not 3/3 speed
                BHBot.logger.warn("1x speed detected, fixing..");
                seg = MarvinSegment.fromCue("Speed", BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                    return;
                }
            } else {
                BHBot.logger.debug("Speed settings checked.");
                speedChecked = true; //if we're full speed we stop checking for this activity
            }
        }

       /*
        *   Merchant offer check
        *   Not super common so we check every 5 seconds
        */
        if (activityDuration % 5 == 0 && encounterStatus) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Merchant"), BHBot.browser);
            if (seg != null) {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Decline"), 5 * SECOND, BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                } else BHBot.logger.error("Merchant 'decline' cue not found");

                BHBot.browser.readScreen(SECOND);
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 5 * SECOND, BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                } else BHBot.logger.error("Merchant 'yes' cue not found");
            }
        }

       /*
        *   Character dialogue check
        *   This is a one time event per account instance, so we don't need to check it very often
        *   encounterStatus is set to true as the dialogue obscures the guild icon
        */
        if (activityDuration % 10 == 0 && encounterStatus && (state == State.Dungeon || state == State.Raid)) {
            detectCharacterDialogAndHandleIt();
        }


        /*
         *  Check for the 'Cleared' dialogue and handle post-activity tasks
         */
        if (state == State.Raid || state == State.Dungeon || state == State.Expedition || state == State.Trials || state == State.UnidentifiedDungeon) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Cleared"), BHBot.browser);
            if (seg != null) {

                //Calculate activity stats
                counters.get(state).increaseVictories();
                long activityRuntime = Misc.getTime() - activityStartTime * 1000; //get elapsed time in milliseconds
                String runtime = Misc.millisToHumanForm(activityRuntime);
                counters.get(state).increaseVictoriesDuration(activityRuntime);
                String runtimeAvg = Misc.millisToHumanForm(counters.get(state).getVictoryAverageDuration());
                //return stats
                BHBot.logger.info(state.getName() + " #" + counters.get(state).getTotal() + " completed. Result: Victory");
                BHBot.logger.stats(state.getName() + " " + counters.get(state).successRateDesc());
                BHBot.logger.stats("Victory run time: " + runtime + ". Average: " + runtimeAvg + ".");

                //handle SuccessThreshold
                handleSuccessThreshold(state);

                //close 'cleared' popup
                closePopupSecurely(BrowserManager.cues.get("Cleared"), BrowserManager.cues.get("YesGreen"));

                // close the activity window to return us to the main screen
                if (state != State.Expedition) {
                    BHBot.browser.readScreen(3 * SECOND); //wait for slide-in animation to finish
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                    if (seg != null) {
                        BHBot.browser.clickOnSeg(seg);
                    } else BHBot.logger.warn("Unable to find close button for " + state.getName() + " window..");
                }

                //For Expedition we need to close 3 windows (Exped/Portal/Team) to return to main screen
                //Next Expedition this'll be replaced with closePopupSecurely
                if (state == State.Expedition) {

                    // first screen
                    BHBot.browser.readScreen(SECOND);
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 3 * SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);

                    // Close Portal Map after expedition
                    BHBot.browser.readScreen(SECOND);
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 3 * SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);

                    // close Expedition window after Expedition
                    BHBot.browser.readScreen(SECOND);
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 3 * SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);
                }

                resetAppropriateTimers();
                resetRevives();
                state = State.Main; // reset state
                return;
            }
        }

        /*
         *  Check for the 'Victory' screen and handle post-activity tasks
         */
        if (state == State.WorldBoss || state == State.Gauntlet || state == State.Invasion || state == State.PVP || state == State.GVG) {
            if (state == State.Gauntlet) {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("VictorySmall"), BHBot.browser);
            } else {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("VictoryLarge"), BHBot.browser);
            }
            if (seg != null) {

                //Calculate activity stats
                counters.get(state).increaseVictories();
                long activityRuntime = Misc.getTime() - activityStartTime * 1000; //get elapsed time in milliseconds
                String runtime = Misc.millisToHumanForm(activityRuntime);
                counters.get(state).increaseVictoriesDuration(activityRuntime);
                String runtimeAvg = Misc.millisToHumanForm(counters.get(state).getVictoryAverageDuration());
                //return stats
                BHBot.logger.info(state.getName() + " #" + counters.get(state).getTotal() + " completed. Result: Victory");
                BHBot.logger.stats(state.getName() + " " + counters.get(state).successRateDesc());
                BHBot.logger.stats("Victory run time: " + runtime + ". Average: " + runtimeAvg + ".");

                //handle SuccessThreshold
                handleSuccessThreshold(state);

                //check for loot drops and send via Pushover/Screenshot
                handleLoot();

                //close the loot window
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("CloseGreen"), 2 * SECOND, BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                } else BHBot.logger.warn("Unable to find close button for " + state.getName() + " victory screen..");

                // close the activity window to return us to the main screen
                BHBot.browser.readScreen(3 * SECOND); //wait for slide-in animation to finish
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                } else BHBot.logger.warn("Unable to find X button for " + state.getName() + " window..");

                //last few post activity tasks
                resetAppropriateTimers();
                resetRevives();
                if (state == State.GVG) dressUp(BHBot.settings.gvgstrip);
                if (state == State.PVP) dressUp(BHBot.settings.pvpstrip);

                //return to main state
                state = State.Main; // reset state
                return;
            }
        }

        /*
         *  Check for the 'Defeat' dialogue and handle post-activity tasks
         *  Most activities have custom tasks on defeat
         */
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Defeat"), BHBot.browser);
        if (seg != null) {


            //Calculate activity stats
            counters.get(state).increaseDefeats();
            long activityRuntime = Misc.getTime() - activityStartTime * 1000; //get elapsed time in milliseconds
            String runtime = Misc.millisToHumanForm(activityRuntime);
            counters.get(state).increaseDefeatsDuration(activityRuntime);
            String runtimeAvg = Misc.millisToHumanForm(counters.get(state).getDefeatAverageDuration());

            //return stats for non-invasion
            if (state != State.Invasion) {
                BHBot.logger.warn(state.getName() + " #" + counters.get(state).getTotal() + " completed. Result: Defeat.");
                BHBot.logger.stats(state.getName() + " " + counters.get(state).successRateDesc());
                BHBot.logger.stats("Defeat run time: " + runtime + ". Average: " + runtimeAvg + ".");
            } else {
                //return the stats for invasion (no victory possible so we skip the warning)
                BHBot.browser.readScreen();
                MarvinImage subm = new MarvinImage(BHBot.browser.getImg().getSubimage(375, 20, 55, 20));
                makeImageBlackWhite(subm, new Color(25, 25, 25), new Color(255, 255, 255), 64);
                BufferedImage subimagetestbw = subm.getBufferedImage();
                int num = readNumFromImg(subimagetestbw, "small", new HashSet<>());
                BHBot.logger.info(state.getName() + " #" + counters.get(state).getTotal() + " completed. Level reached: " + num);
                BHBot.logger.stats("Run time: " + runtime + ". Average: " + runtimeAvg + ".");
            }

            //in Gauntlet/Invasion the close button is green, everywhere else its blue
            if (state == State.Gauntlet || state == State.Invasion) {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("CloseGreen"), 2 * SECOND, BHBot.browser);
            } else {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Close"), 2 * SECOND, BHBot.browser);
            }

            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
            } else {
                BHBot.logger.warn("Problem: 'Defeat' popup detected but no 'Close' button detected in " + state.getName() + ".");
                if (state == State.PVP) dressUp(BHBot.settings.pvpstrip);
                if (state == State.GVG) dressUp(BHBot.settings.gvgstrip);
                return;
            }


            //Close the activity window to return us to the main screen
            if (state != State.Expedition) {
                BHBot.browser.readScreen(3 * SECOND); //wait for slide-in animation to finish
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                if (seg != null) {
                    BHBot.browser.clickOnSeg(seg);
                } else BHBot.logger.warn("Unable to find X button for " + state.getName() + " window..");
            }

            //For Expedition we need to close 3 windows (Exped/Portal/Team) to return to main screen
            //Next Expedition this'll be replaced with closePopupSecurely
            if (state == State.Expedition) {
                // first screen
                BHBot.browser.readScreen(SECOND);
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 3 * SECOND, BHBot.browser);
                BHBot.browser.clickOnSeg(seg);

                // Close Portal Map after expedition
                BHBot.browser.readScreen(SECOND);
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 3 * SECOND, BHBot.browser);
                BHBot.browser.clickOnSeg(seg);

                // close Expedition window after Expedition
                BHBot.browser.readScreen(SECOND);
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 3 * SECOND, BHBot.browser);
                BHBot.browser.clickOnSeg(seg);

                //Handle difficultyFailsafe for Exped
                if (BHBot.settings.difficultyFailsafe.containsKey("e")) {
                    // The key is the difficulty decrease, the value is the minimum level
                    Map.Entry<Integer, Integer> expedDifficultyFailsafe = BHBot.settings.difficultyFailsafe.get("e");
                    int levelOffset = expedDifficultyFailsafe.getKey();
                    int minimumLevel = expedDifficultyFailsafe.getValue();

                    // We check that the level offset for expedition is a multiplier of 5
                    int levelOffsetModule = levelOffset % 5;
                    if (levelOffsetModule != 0) {
                        int newLevelOffset = levelOffset + (5 - levelOffsetModule);
                        BHBot.logger.warn("Level offset " + levelOffset + " is not multiplier of 5, rounding it to " + newLevelOffset);
                        BHBot.settings.difficultyFailsafe.put("e", Maps.immutableEntry(newLevelOffset, minimumLevel));
                    }

                    // We calculate the new difficulty
                    int newExpedDifficulty = expeditionFailsafeDifficulty - levelOffset;

                    // We check that the new difficulty is not lower than the minimum
                    if (newExpedDifficulty < minimumLevel) newExpedDifficulty = minimumLevel;
                    if (newExpedDifficulty < 5) newExpedDifficulty = 5;

                    // If the new difficulty is different from the current one, we update the ini setting
                    if (newExpedDifficulty != expeditionFailsafeDifficulty) {
                        String original = expeditionFailsafePortal + " " + expeditionFailsafeDifficulty;
                        String updated = expeditionFailsafePortal + " " + newExpedDifficulty;
                        settingsUpdate(original, updated);
                    }
                }
            }

            if (state.equals(State.Trials) && BHBot.settings.difficultyFailsafe.containsKey("t")) {
                // The key is the difficulty decrease, the value is the minimum level
                Map.Entry<Integer, Integer> trialDifficultyFailsafe = BHBot.settings.difficultyFailsafe.get("t");
                int levelOffset = trialDifficultyFailsafe.getKey();
                int minimumLevel = trialDifficultyFailsafe.getValue();

                // We calculate the new difficulty
                int newTrialDifficulty = BHBot.settings.difficultyTrials - levelOffset;

                // We check that the new difficulty is not lower than the minimum
                if (newTrialDifficulty < minimumLevel) newTrialDifficulty = minimumLevel;

                // If the new difficulty is different from the current one, we update the ini setting
                if (newTrialDifficulty != BHBot.settings.difficultyTrials) {
                    String original = "difficultyTrials " + BHBot.settings.difficultyTrials;
                    String updated = "difficultyTrials " + newTrialDifficulty;
                    settingsUpdate(original, updated);
                }
            }

            if (state.equals(State.Gauntlet) && BHBot.settings.difficultyFailsafe.containsKey("g")) {
                // The key is the difficulty decrease, the value is the minimum level
                Map.Entry<Integer, Integer> gauntletDifficultyFailsafe = BHBot.settings.difficultyFailsafe.get("g");
                int levelOffset = gauntletDifficultyFailsafe.getKey();
                int minimumLevel = gauntletDifficultyFailsafe.getValue();

                // We calculate the new difficulty
                int newGauntletDifficulty = BHBot.settings.difficultyGauntlet - levelOffset;

                // We check that the new difficulty is not lower than the minimum
                if (newGauntletDifficulty < minimumLevel) newGauntletDifficulty = minimumLevel;

                // If the new difficulty is different from the current one, we update the ini setting
                if (newGauntletDifficulty != BHBot.settings.difficultyGauntlet) {
                    String original = "difficultyGauntlet " + BHBot.settings.difficultyGauntlet;
                    String updated = "difficultyGauntlet " + newGauntletDifficulty;
                    settingsUpdate(original, updated);
                }
            }

            resetAppropriateTimers();
            resetRevives();

            // We make sure to dress up
            if (state == State.PVP && BHBot.settings.pvpstrip.size() > 0) dressUp(BHBot.settings.pvpstrip);
            if (state == State.GVG && BHBot.settings.gvgstrip.size() > 0) dressUp(BHBot.settings.gvgstrip);

            // We make sure to disable autoshrine when defeated
            if (state == State.Trials || state == State.Raid || state == State.Expedition) {
                if (ignoreBossSetting && ignoreShrinesSetting) {
                    BHBot.browser.readScreen(SECOND);
                    if (!checkShrineSettings(false, false)) {
                        BHBot.logger.error("Impossible to disable autoShrine after defeat! Restarting..");
                        restart();
                    }
                }
                autoShrined = false;
                autoBossRuned = false;
                BHBot.browser.readScreen(SECOND * 2);
            }

            state = State.Main; // reset state
            return;
        }

        // at the end of processDungeon, we revert idle time change (in order for idle detection to function properly):
        BHBot.scheduler.restoreIdleTime();
    }

    private void handleAutoBossRune() { //seperate function so we can run autoRune without autoShrine
        MarvinSegment guildButtonSeg;
        guildButtonSeg = MarvinSegment.fromCue(BrowserManager.cues.get("GuildButton"), BHBot.browser);

        if ((state == State.Raid && !BHBot.settings.autoShrine.contains("r") && BHBot.settings.autoBossRune.containsKey("r")) ||
            (state == State.Trials && !BHBot.settings.autoShrine.contains("t") && BHBot.settings.autoBossRune.containsKey("t")) ||
            (state == State.Expedition && !BHBot.settings.autoShrine.contains("e") && BHBot.settings.autoBossRune.containsKey("e")) ||
            (state == State.Dungeon && BHBot.settings.autoBossRune.containsKey("d")) ||
            state == State.UnidentifiedDungeon) {
            if (!autoBossRuned) {
                if ((((outOfEncounterTimestamp - inEncounterTimestamp) >= BHBot.settings.battleDelay) && guildButtonSeg != null)) {
                    BHBot.logger.autorune(BHBot.settings.battleDelay + "s since last encounter, changing runes for boss encounter");

                    handleMinorBossRunes();

                    if (!checkShrineSettings(false, false)) {
                        BHBot.logger.error("Impossible to disable Ignore Boss in handleAutoBossRune!");
                        BHBot.logger.warn("Resetting encounter timer to try again in 30 seconds.");
                        inEncounterTimestamp = Misc.getTime() / 1000;
                        return;
                    }

                    // We disable and re-enable the auto feature
                    while (MarvinSegment.fromCue(BrowserManager.cues.get("AutoOn"), 500, BHBot.browser) != null) {
                        BHBot.browser.clickInGame(780, 270); //auto off
                        BHBot.browser.readScreen(500);
                    }
                    while (MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), 500, BHBot.browser) != null) {
                        BHBot.browser.clickInGame(780, 270); //auto on again
                        BHBot.browser.readScreen(500);
                    }
                    autoBossRuned = true;
                }
            }
        }
    }

    private void handleAutoShrine() {
        MarvinSegment guildButtonSeg;
        guildButtonSeg = MarvinSegment.fromCue(BrowserManager.cues.get("GuildButton"), BHBot.browser);

        if ((state == State.Raid && BHBot.settings.autoShrine.contains("r")) ||
            (state == State.Trials && BHBot.settings.autoShrine.contains("t")) ||
            (state == State.Expedition && BHBot.settings.autoShrine.contains("e")) ||
            (state == State.UnidentifiedDungeon)) {
            if (!autoShrined) {
                if ((((outOfEncounterTimestamp - inEncounterTimestamp) >= BHBot.settings.battleDelay) && guildButtonSeg != null)) {
                    BHBot.logger.autorune(BHBot.settings.battleDelay + "s since last encounter, disabling ignore shrines");

                    if (!checkShrineSettings(true, false)) {
                        BHBot.logger.error("Impossible to disable Ignore Shrines in handleAutoShrine!");
                        return;
                    }
                    BHBot.browser.readScreen(100);

                    // We disable and re-enable the auto feature
                    while (MarvinSegment.fromCue(BrowserManager.cues.get("AutoOn"), 500, BHBot.browser) != null) {
                        BHBot.browser.clickInGame(780, 270); //auto off
                        BHBot.browser.readScreen(500);
                    }
                    while (MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), 500, BHBot.browser) != null) {
                        BHBot.browser.clickInGame(780, 270); //auto on again
                        BHBot.browser.readScreen(500);
                    }

                    if ((state == State.Raid && BHBot.settings.autoBossRune.containsKey("r")) || (state == State.Trials && BHBot.settings.autoBossRune.containsKey("t")) ||
                            (state == State.Expedition && BHBot.settings.autoBossRune.containsKey("e")) || (state == State.Dungeon && BHBot.settings.autoBossRune.containsKey("d"))) {
                        handleMinorBossRunes();
                    } else {
                        BHBot.logger.autoshrine("Waiting " + BHBot.settings.shrineDelay + "s to use shrines");
                        Misc.sleep(BHBot.settings.shrineDelay * SECOND); //if we're not changing runes we sleep while we activate shrines
                    }

                    if (!checkShrineSettings(false, false)) {
                        BHBot.logger.error("Impossible to disable Ignore Boss in handleAutoShrine!");
                        return;
                    }
                    BHBot.browser.readScreen(100);

                    // We disable and re-enable the auto feature
                    while (MarvinSegment.fromCue(BrowserManager.cues.get("AutoOn"), 500, BHBot.browser) != null) {
                        BHBot.browser.clickInGame(780, 270); //auto off
                        BHBot.browser.readScreen(500);
                    }
                    while (MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), 500, BHBot.browser) != null) {
                        BHBot.browser.clickInGame(780, 270); //auto on again
                        BHBot.browser.readScreen(500);
                    }

                    autoShrined = true;
                    BHBot.scheduler.resetIdleTime(true);
                }
            }
        }
    }

    private void handleMinorRunes(String activity) {
        List<String> desiredRunesAsStrs;
        String activityName = state.getNameFromShortcut(activity);
        if (BHBot.settings.autoRuneDefault.isEmpty()) {
            BHBot.logger.debug("autoRunesDefault not defined; aborting autoRunes");
            return;
        }

        if (!BHBot.settings.autoRune.containsKey(activity)) {
            BHBot.logger.debug("No autoRunes assigned for " + activityName + ", using defaults.");
            desiredRunesAsStrs = BHBot.settings.autoRuneDefault;
        } else {
            BHBot.logger.info("Configuring autoRunes for " + activityName);
            desiredRunesAsStrs = BHBot.settings.autoRune.get(activity);
        }

        List<MinorRuneEffect> desiredRunes = resolveDesiredRunes(desiredRunesAsStrs);
        if (noRunesNeedSwitching(desiredRunes)) {
            return;
        }

        // Back out of any raid/gauntlet/trial/GvG/etc pre-menu
        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 2 * SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);
        }

        if (!switchMinorRunes(desiredRunes))
            BHBot.logger.info("AutoRune failed!");

    }

    private void handleMinorBossRunes() {
        if (BHBot.settings.autoRuneDefault.isEmpty()) {
            BHBot.logger.debug("autoRunesDefault not defined; aborting autoBossRunes");
            return;
        }

        String activity = state.getShortcut();
        // Hack to work around unknown dungeons
        if (activity.equals("ud"))
            activity = "d";
        if (!BHBot.settings.autoBossRune.containsKey(activity)) {
            BHBot.logger.info("No autoBossRunes assigned for " + state.getName() + ", skipping.");
            return;
        }

        List<String> desiredRunesAsStrs = BHBot.settings.autoBossRune.get(activity);
        List<MinorRuneEffect> desiredRunes = resolveDesiredRunes(desiredRunesAsStrs);
        if (noRunesNeedSwitching(desiredRunes))
            return;

        if (!switchMinorRunes(desiredRunes))
            BHBot.logger.autorune("AutoBossRune failed!");

    }

    private List<MinorRuneEffect> resolveDesiredRunes(List<String> desiredRunesAsStrs) {
        List<MinorRuneEffect> desiredRunes = new ArrayList<>();

        if (desiredRunesAsStrs.size() != 2) {
            BHBot.logger.error("Got malformed autoRunes, using defaults: " + String.join(" ", desiredRunesAsStrs));
            desiredRunesAsStrs = BHBot.settings.autoRuneDefault;
        }

        String strLeftRune = desiredRunesAsStrs.get(0);
        MinorRuneEffect desiredLeftRune = MinorRuneEffect.getEffectFromName(strLeftRune);
        if (desiredLeftRune == null) {
            BHBot.logger.error("No rune type found for left rune name " + strLeftRune);
            desiredLeftRune = leftMinorRune.getRuneEffect();
        }
        desiredRunes.add(desiredLeftRune);

        String strRightRune = desiredRunesAsStrs.get(1);
        MinorRuneEffect desiredRightRune = MinorRuneEffect.getEffectFromName(strRightRune);
        if (desiredRightRune == null) {
            BHBot.logger.error("No rune type found for right rune name " + strRightRune);
            desiredRightRune = rightMinorRune.getRuneEffect();
        }

        desiredRunes.add(desiredRightRune);

        return desiredRunes;
    }

    private boolean noRunesNeedSwitching(List<MinorRuneEffect> desiredRunes) {
        MinorRuneEffect desiredLeftRune = desiredRunes.get(0);
        MinorRuneEffect desiredRightRune = desiredRunes.get(1);
        MinorRuneEffect currentLeftRune = leftMinorRune.getRuneEffect();
        MinorRuneEffect currentRightRune = rightMinorRune.getRuneEffect();

        if (desiredLeftRune == currentLeftRune && desiredRightRune == currentRightRune) {
            BHBot.logger.debug("No runes found that need switching.");
            return true; // Nothing to do
        }

        if (desiredLeftRune != currentLeftRune) {
            BHBot.logger.debug("Left minor rune needs to be switched.");
        }
        if (desiredRightRune != currentRightRune) {
            BHBot.logger.debug("Right minor rune needs to be switched.");
        }

        return false;

    }

    private Boolean switchMinorRunes(List<MinorRuneEffect> desiredRunes) {
        MinorRuneEffect desiredLeftRune = desiredRunes.get(0);
        MinorRuneEffect desiredRightRune = desiredRunes.get(1);

        if (!detectEquippedMinorRunes(true, false)) {
            BHBot.logger.error("Unable to detect runes, pre-equip.");
            return false;
        }

        if (desiredLeftRune != leftMinorRune.getRuneEffect()) {
            BHBot.logger.debug("Switching left minor rune.");
            Misc.sleep(500); //sleep for window animation to finish
            BHBot.browser.clickInGame(280, 290); // Click on left rune
            if (!switchSingleMinorRune(desiredLeftRune)) {
                BHBot.logger.error("Failed to switch left minor rune.");
                return false;
            }
        }


        if (desiredRightRune != rightMinorRune.getRuneEffect()) {
            BHBot.logger.debug("Switching right minor rune.");
            Misc.sleep(500); //sleep for window animation to finish
            BHBot.browser.clickInGame(520, 290); // Click on right rune
            if (!switchSingleMinorRune(desiredRightRune)) {
                BHBot.logger.error("Failed to switch right minor rune.");
                return false;
            }
        }

        Misc.sleep(SECOND); //sleep while we wait for window animation

        if (!detectEquippedMinorRunes(false, true)) {
            BHBot.logger.error("Unable to detect runes, post-equip.");
            return false;
        }

        Misc.sleep(2 * SECOND);
        boolean success = true;
        if (desiredLeftRune != leftMinorRune.getRuneEffect()) {
            BHBot.logger.error("Left minor rune failed to switch for unknown reason.");
            success = false;
        }
        if (desiredRightRune != rightMinorRune.getRuneEffect()) {
            BHBot.logger.error("Right minor rune failed to switch for unknown reason.");
            success = false;
        }

        return success;
    }

    private Boolean switchSingleMinorRune(MinorRuneEffect desiredRune) {

        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("RunesSwitch"), 5 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.error("Failed to find rune switch button.");
            return false;
        }
        BHBot.browser.clickOnSeg(seg);

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("RunesPicker"), 5 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.error("Failed to find rune picker.");
            return false;
        }

        ItemGrade maxRuneGrade = MinorRune.maxGrade;
        for (int runeGradeVal = maxRuneGrade.getValue(); runeGradeVal > 0; runeGradeVal--) {
            ItemGrade runeGrade = ItemGrade.getGradeFromValue(runeGradeVal);
            MinorRune thisRune = MinorRune.getRune(desiredRune, runeGrade);

            if (thisRune == null) {
                BHBot.logger.error("Unable to getRune in switchSingleMinorRune");
                continue;
            }

            Cue runeCue = thisRune.getRuneSelectCue();
            if (runeCue == null) {
                BHBot.logger.error("Unable to find cue for rune " + getRuneName(thisRune.getRuneCueName()));
                continue;
            }
            seg = MarvinSegment.fromCue(runeCue, BHBot.browser);
            if (seg == null) {
                BHBot.logger.debug("Unable to find " + getRuneName(thisRune.getRuneCueName()) + " in rune picker.");
                continue;
            }
            BHBot.logger.autorune("Switched to " + getRuneName(thisRune.getRuneCueName()));
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(SECOND);
            return true;
        }

        BHBot.logger.error("Unable to find rune of type " + desiredRune);
        closePopupSecurely(BrowserManager.cues.get("RunesPicker"), BrowserManager.cues.get("X"));
        Misc.sleep(SECOND);
        return false;
    }

    /**
     * Function to return the name of the runes for console output
     */
    private String getRuneName(String runeName) {

        switch (runeName) {
            case "MinorRuneExperienceCommon":
                return "Common Experience";
            case "MinorRuneExperienceRare":
                return "Rare Experience";
            case "MinorRuneExperienceEpic":
                return "Epic Experience";
            case "MinorRuneExperienceLegendary":
                return "Legendary Experience";
            case "MinorRuneItem_FindCommon":
                return "Common Item Find";
            case "MinorRuneItem_FindRare":
                return "Rare Item Find";
            case "MinorRuneItem_FindEpic":
                return "Epic Item Find";
            case "MinorRuneItem_FindLegendary":
                return "Legendary Item Find";
            case "MinorRuneGoldCommon":
                return "Common Gold";
            case "MinorRuneGoldRare":
                return "Rare Gold";
            case "MinorRuneGoldEpic":
                return "Epic Gold";
            case "MinorRuneGoldLegendary":
                return "Legendary Gold";
            case "MinorRuneCaptureCommon":
                return "Common Capture";
            case "MinorRuneCaptureRare":
                return "Rare Capture";
            case "MinorRuneCaptureEpic":
                return "Epic Capture";
            case "MinorRuneCaptureLegendary":
                return "Legendary Capture";
            default:
                return null;
        }
    }

    private boolean handleSkeletonKey() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("SkeletonNoKeys"), 2 * SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.logger.warn("No skeleton keys, skipping..");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Decline"), 5 * SECOND, BHBot.browser);
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 5 * SECOND, BHBot.browser);
            BHBot.browser.clickOnSeg(seg);
            return false;
        }

        if (BHBot.settings.openSkeleton == 0) {
            BHBot.logger.info("Skeleton treasure found, declining.");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Decline"), 5 * SECOND, BHBot.browser);
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 5 * SECOND, BHBot.browser);
            BHBot.browser.clickOnSeg(seg);
            return false;

        } else if (BHBot.settings.openSkeleton == 1) {
            BHBot.logger.info("Skeleton treasure found, attemping to use key");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Open"), 5 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Open button not found, restarting");
                String STScreen = saveGameScreen("skeleton-treasure-no-open");
                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                    if (STScreen != null) {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without OPEN button", "siren", MessagePriority.NORMAL, new File(STScreen));
                    } else {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without OPEN button", "siren", MessagePriority.NORMAL, null);
                    }
                }
                return true;
            }
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 5 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Yes button not found, restarting");
                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                    String STScreen = saveGameScreen("skeleton-treasure-no-yes");
                    if (STScreen != null) {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without YES button", "siren", MessagePriority.NORMAL, new File(STScreen));
                    } else {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without YES button", "siren", MessagePriority.NORMAL, null);
                    }
                }
                return true;
            }
            BHBot.browser.clickOnSeg(seg);
            return false;

        } else if (BHBot.settings.openSkeleton == 2 && state == State.Raid) {
            BHBot.logger.info("Raid Skeleton treasure found, attemping to use key");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Open"), 5 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Open button not found, restarting");
                String STScreen = saveGameScreen("skeleton-treasure-no-open");
                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                    if (STScreen != null) {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without OPEN button", "siren", MessagePriority.NORMAL, new File(STScreen));
                    } else {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without OPEN button", "siren", MessagePriority.NORMAL, null);
                    }
                }
                return true;
            }
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 5 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Yes button not found, restarting");
                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                    String STScreen = saveGameScreen("skeleton-treasure-no-yes");
                    if (STScreen != null) {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without YES button", "siren", MessagePriority.NORMAL, new File(STScreen));
                    } else {
                        sendPushOverMessage("Treasure chest error", "Skeleton Chest gump without YES button", "siren", MessagePriority.NORMAL, null);
                    }
                }
                return true;
            }
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(500);
            if ((BHBot.settings.screenshots.contains("s"))) {
                saveGameScreen("skeleton-contents", "rewards");
            }
            return false;

        } else
            BHBot.logger.info("Skeleton treasure found, declining.");
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Decline"), 5 * SECOND, BHBot.browser);
        BHBot.browser.clickOnSeg(seg);
        BHBot.browser.readScreen(SECOND);
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 5 * SECOND, BHBot.browser);
        BHBot.browser.clickOnSeg(seg);
        return false;
    }

    private void handleFamiliarEncounter() {
        MarvinSegment seg;

        BHBot.logger.autobribe("Familiar encountered");
        BHBot.browser.readScreen(2 * SECOND);

        FamiliarType familiarLevel;
        if (MarvinSegment.fromCue(BrowserManager.cues.get("CommonFamiliar"), BHBot.browser) != null) {
            familiarLevel = FamiliarType.COMMON;
        } else if (MarvinSegment.fromCue(BrowserManager.cues.get("RareFamiliar"), BHBot.browser) != null) {
            familiarLevel = FamiliarType.RARE;
        } else if (MarvinSegment.fromCue(BrowserManager.cues.get("EpicFamiliar"), BHBot.browser) != null) {
            familiarLevel = FamiliarType.EPIC;
        } else if (MarvinSegment.fromCue(BrowserManager.cues.get("LegendaryFamiliar"), BHBot.browser) != null) {
            familiarLevel = FamiliarType.LEGENDARY;
        } else {
            familiarLevel = FamiliarType.ERROR; // error
        }

        PersuationType persuasion;
        BribeDetails bribeInfo = new BribeDetails();

        // Checking familiars setting takes time and a lot of cues verifications. We try to minimize the number of times
        // this is done
        boolean skipBribeNames = false;
        if ((BHBot.settings.bribeLevel > 0 && familiarLevel.getValue() >= BHBot.settings.bribeLevel) ||
                (BHBot.settings.familiars.size() == 0)) {
            skipBribeNames = true;
        }

        if (!skipBribeNames) {
            bribeInfo = verifyBribeNames();
        }

        if ((BHBot.settings.bribeLevel > 0 && familiarLevel.getValue() >= BHBot.settings.bribeLevel) ||
                bribeInfo.toBribeCnt > 0) {
            persuasion = PersuationType.BRIBE;
        } else if ((BHBot.settings.persuasionLevel > 0 && familiarLevel.getValue() >= BHBot.settings.persuasionLevel)) {
            persuasion = PersuationType.PERSUADE;
        } else {
            persuasion = PersuationType.DECLINE;
        }

        // If we're set to bribe and we don't have gems, we default to PERSUASION
        if (persuasion == PersuationType.BRIBE && noGemsToBribe) {
            persuasion = PersuationType.PERSUADE;
        }

        StringBuilder persuasionLog = new StringBuilder("familiar-");
        persuasionLog.append(familiarLevel.toString().toUpperCase()).append("-");
        persuasionLog.append(persuasion.toString().toUpperCase()).append("-");
        persuasionLog.append("attempt");

        // We save all the errors and persuasions based on settings
        if ((familiarLevel.getValue() >= BHBot.settings.familiarScreenshot) || familiarLevel == FamiliarType.ERROR) {
            saveGameScreen(persuasionLog.toString(), "familiars");

            if (BHBot.settings.contributeFamiliars) {
                contributeFamiliarShoot(persuasionLog.toString(), familiarLevel);
            }
        }

        // We attempt persuasion or bribe based on settings
        if (persuasion == PersuationType.BRIBE) {
            if (!bribeFamiliar()) {
                BHBot.logger.autobribe("Bribe attempt failed! Trying with persuasion...");
                if (persuadeFamiliar()) {
                    BHBot.logger.autobribe(familiarLevel.toString().toUpperCase() + " persuasion attempted.");
                } else {
                    BHBot.logger.error("Impossible to persuade familiar, restarting...");
                    restart();
                }
            } else {
                updateFamiliarCounter(bribeInfo.familiarName.toUpperCase());
            }
        } else if (persuasion == PersuationType.PERSUADE) {
            if (persuadeFamiliar()) {
                BHBot.logger.autobribe(familiarLevel.toString().toUpperCase() + " persuasion attempted.");
            } else {
                BHBot.logger.error("Impossible to attempt persuasion, restarting.");
                restart();
            }
        } else {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("DeclineRed"), BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg); // seg = detectCue(cues.get("Persuade"))
                BHBot.browser.readScreen(SECOND * 2);
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), SECOND, BHBot.browser);
                BHBot.browser.clickOnSeg(seg);
                BHBot.logger.autobribe(familiarLevel.toString().toUpperCase() + " persuasion declined.");
            } else {
                BHBot.logger.error("Impossible to find the decline button, restarting...");
                restart();
            }
        }
    }

    /**
     * Will verify if in the current persuasion screen one of the bribeNames is present
     */
    private BribeDetails verifyBribeNames() {

        BooleanSupplier openView = () -> {
            MarvinSegment seg;
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("View"), SECOND * 3, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
                BHBot.browser.readScreen(SECOND * 2);
                return true;
            } else {
                return false;
            }
        };

        BooleanSupplier closeView = () -> {
            MarvinSegment seg;
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 2 * SECOND, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
                BHBot.browser.readScreen(SECOND);
                return true;
            } else {
                return false;
            }
        };

        List<String> wrongNames = new ArrayList<>();
        BribeDetails result = new BribeDetails();
        String familiarName;
        int toBribeCnt;

        boolean viewIsOpened = false;

        BHBot.browser.readScreen(SECOND);
        for (String familiarDetails : BHBot.settings.familiars) {
            // familiar details from settings
            String[] details = familiarDetails.toLowerCase().split(" ");
            familiarName = details[0];
            toBribeCnt = Integer.parseInt(details[1]);

            // cue related stuff
            boolean isOldFormat = false;

            Cue familiarCue = BrowserManager.cues.getOrDefault(familiarName, null);

            if (familiarCue == null) {
                familiarCue = BrowserManager.cues.getOrDefault("old" + familiarName, null);
                if (familiarCue != null) isOldFormat = true;
            }

            if (familiarCue != null) {
                if (toBribeCnt > 0) {
                    if (isOldFormat) { // Old style familiar
                        if (!viewIsOpened) { // we try to open the view menu if closed
                            if (openView.getAsBoolean()) {
                                BHBot.browser.readScreen(SECOND * 2);
                                viewIsOpened = true;
                            } else {
                                BHBot.logger.error("Old format familiar with no view button");
                                restart();
                            }
                        }
                    } else { // New style familiar
                        if (viewIsOpened) { // we try to close the view menu if opened
                            if (closeView.getAsBoolean()) {
                                BHBot.browser.readScreen(SECOND);
                                viewIsOpened = false;
                            } else {
                                BHBot.logger.error("Old style familiar detected with no X button to close the view menu.");
                                restart();
                            }
                        }
                    }

                    if (MarvinSegment.fromCue(familiarCue, SECOND * 3, BHBot.browser) != null) {
                        BHBot.logger.autobribe("Detected familiar " + familiarDetails + " as valid in familiars");
                        result.toBribeCnt = toBribeCnt;
                        result.familiarName = familiarName;
                        break;

                    }

                } else {
                    BHBot.logger.warn("Count for familiar " + familiarName + " is 0! Temporary removing it form the settings...");
                    wrongNames.add(familiarDetails);
                }
            } else {
                BHBot.logger.warn("Impossible to find a cue for familiar " + familiarName + ", it will be temporary removed from settings.");
                wrongNames.add(familiarDetails);
            }
        }

        if (viewIsOpened) {
            if (!closeView.getAsBoolean()) {
                BHBot.logger.error("Impossible to close view menu at the end of familiar setting loop!");
                restart();
            }
        }

        // If there is any error we update the settings
        for (String wrongName : wrongNames) {
            BHBot.settings.familiars.remove(wrongName);
        }

        return result;
    }

    private boolean bribeFamiliar() {
        BHBot.browser.readScreen();
        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("Bribe"), SECOND * 3, BHBot.browser);
        BufferedImage tmpScreen = BHBot.browser.getImg();

        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(2 * SECOND);

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), SECOND * 5, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
                Misc.sleep(2 * SECOND);
            }

            if (MarvinSegment.fromCue(BrowserManager.cues.get("NotEnoughGems"), SECOND * 5, BHBot.browser) != null) {
                BHBot.logger.warn("Not enough gems to attempt a bribe!");
                noGemsToBribe = true;
                if (!closePopupSecurely(BrowserManager.cues.get("NotEnoughGems"), BrowserManager.cues.get("No"))) {
                    BHBot.logger.error("Impossible to close the Not Enough gems pop-up. Restarting...");
                    restart();
                }
                return false;
            }
            if (BHBot.settings.enablePushover && BHBot.settings.poNotifyBribe) {
                String bribeScreenName = saveGameScreen("bribe-screen", tmpScreen);
                File bribeScreenFile = bribeScreenName != null ? new File(bribeScreenName) : null;
                sendPushOverMessage("Creature Bribe", "A familiar has been bribed!", "bugle", MessagePriority.NORMAL, bribeScreenFile);
                if (bribeScreenFile != null && !bribeScreenFile.delete())
                    BHBot.logger.warn("Impossible to delete tmp img file for bribe.");
            }
            return true;
        }

        return false;
    }

    private boolean persuadeFamiliar() {

        MarvinSegment seg;
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Persuade"), BHBot.browser);
        if (seg != null) {

            BHBot.browser.clickOnSeg(seg); // seg = detectCue(cues.get("Persuade"))
            Misc.sleep(2 * SECOND);

            BHBot.browser.readScreen();
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), BHBot.browser);
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(2 * SECOND);

            return true;
        }

        return false;
    }

    private void handleAutoRevive() {
        MarvinSegment seg;

        // Auto Revive is disabled, we re-enable it
        if ((BHBot.settings.autoRevive.size() == 0) || (state != State.Trials && state != State.Gauntlet
                && state != State.Raid && state != State.Expedition)) {
            BHBot.logger.debug("AutoRevive disabled, reenabling auto.. State = '" + state + "'");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), BHBot.browser);
            if (seg != null) BHBot.browser.clickOnSeg(seg);
            BHBot.scheduler.resetIdleTime(true);
            return;
        }

        // if everyone dies autoRevive attempts to revive people on the defeat screen, this should prevent that
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Defeat"), SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.logger.autorevive("Defeat screen, skipping revive check");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), SECOND, BHBot.browser);
            if (seg != null) BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);
            BHBot.scheduler.resetIdleTime(true);
            return;
        }

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("VictoryLarge"), 500, BHBot.browser);
        if (seg != null) {
            BHBot.logger.autorevive("Victory popup, skipping revive check");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), SECOND, BHBot.browser);
            if (seg != null) BHBot.browser.clickOnSeg(seg);

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("CloseGreen"), 2 * SECOND, BHBot.browser); // after enabling auto again the bot would get stuck at the victory screen, this should close it
            if (seg != null)
                BHBot.browser.clickOnSeg(seg);
            else {
                BHBot.logger.warn("Problem: 'Victory' window has been detected, but no 'Close' button. Ignoring...");
                return;
            }
            BHBot.scheduler.resetIdleTime(true);
            return;
        }

        // we make sure that we stick with the limits
        if (potionsUsed >= BHBot.settings.potionLimit) {
            BHBot.logger.autorevive("Potion limit reached, skipping revive check");
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), SECOND, BHBot.browser);
            if (seg != null) BHBot.browser.clickOnSeg(seg);
            BHBot.scheduler.resetIdleTime(true);
            return;
        }

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Potions"), SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen(SECOND);

            // If no potions are needed, we re-enable the Auto function
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("NoPotions"), SECOND, BHBot.browser); // Everyone is Full HP
            if (seg != null) {
                seg = MarvinSegment.fromCue("Close", SECOND, new Bounds(300, 330, 500, 400), BHBot.browser);
                if (seg != null) {
                    BHBot.logger.autorevive("None of the team members need a consumable, exiting from autoRevive");
                    BHBot.browser.clickOnSeg(seg);
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), SECOND, BHBot.browser);
                    BHBot.browser.clickOnSeg(seg);
                } else {
                    BHBot.logger.error("No potions cue detected, without close button, restarting!");
                    saveGameScreen("autorevive-no-potions-no-close", BHBot.browser.getImg());
                    restart();
                }
                BHBot.scheduler.resetIdleTime(true);
                return;
            }

            // Based on the state we get the team size
            HashMap<Integer, Point> revivePositions = new HashMap<>();
            switch (state) {
                case Trials:
                case Gauntlet:
                case Expedition:
                    revivePositions.put(1, new Point(290, 315));
                    revivePositions.put(2, new Point(200, 340));
                    revivePositions.put(3, new Point(115, 285));
                    break;
                case Raid:
                    revivePositions.put(1, new Point(305, 320));
                    revivePositions.put(2, new Point(250, 345));
                    revivePositions.put(3, new Point(200, 267));
                    revivePositions.put(4, new Point(150, 325));
                    revivePositions.put(5, new Point(90, 295));
                    break;
                default:
                    break;
            }

            if ((state == State.Trials && BHBot.settings.autoRevive.contains("t")) ||
                    (state == State.Gauntlet && BHBot.settings.autoRevive.contains("g")) ||
                    (state == State.Raid && BHBot.settings.autoRevive.contains("r")) ||
                    (state == State.Expedition && BHBot.settings.autoRevive.contains("e"))) {

                // from char to potion name
                HashMap<Character, String> potionTranslate = new HashMap<>();
                potionTranslate.put('1', "Minor");
                potionTranslate.put('2', "Average");
                potionTranslate.put('3', "Major");

                //for loop for each entry in revivePositions
                for (Map.Entry<Integer, Point> item : revivePositions.entrySet()) {
                    Integer slotNum = item.getKey();
                    Point slotPos = item.getValue();

                    //if we have reached potionLimit we exit autoRevive
                    if (potionsUsed == BHBot.settings.potionLimit) {
                        BHBot.logger.autorevive("Potion limit reached, exiting from Auto Revive");
                        BHBot.browser.readScreen(SECOND);
                        break;
                    }

                    //if position has been revived don't check it again
                    if (revived[slotNum - 1]) continue;

                    //check if there is a gravestone to see if we need to revive
                    //we MouseOver to make sure the grave is in the foreground and not covered
                    BHBot.browser.moveMouseToPos(slotPos.x, slotPos.y);
                    if (MarvinSegment.fromCue(BrowserManager.cues.get("GravestoneHighlighted"), 3 * SECOND, BHBot.browser) == null) continue;

                    // If we revive a team member we need to reopen the potion menu again
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("UnitSelect"), SECOND, BHBot.browser);
                    if (seg == null) {
                        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Potions"), SECOND * 2, BHBot.browser);
                        if (seg != null) {
                            BHBot.browser.clickOnSeg(seg);
                            BHBot.browser.readScreen(SECOND);

                            // If no potions are needed, we re-enable the Auto function
                            seg = MarvinSegment.fromCue(BrowserManager.cues.get("NoPotions"), SECOND, BHBot.browser); // Everyone is Full HP
                            if (seg != null) {
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("Close"), SECOND, new Bounds(300, 330, 500, 400), BHBot.browser);
                                if (seg != null) {
                                    BHBot.logger.autorevive("None of the team members need a consumable, exiting from autoRevive");
                                    BHBot.browser.clickOnSeg(seg);
                                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), SECOND, BHBot.browser);
                                    BHBot.browser.clickOnSeg(seg);
                                } else {
                                    BHBot.logger.error("Error while reopening the potions menu: no close button found!");
                                    saveGameScreen("autorevive-no-potions-for-error", BHBot.browser.getImg());
                                    restart();
                                }
                                return;
                            }
                        }
                    }

                    BHBot.browser.readScreen(SECOND);
                    BHBot.browser.clickInGame(slotPos.x, slotPos.y);
                    BHBot.browser.readScreen(SECOND);

                    MarvinSegment superHealSeg = MarvinSegment.fromCue(BrowserManager.cues.get("SuperAvailable"), BHBot.browser);

                    if (superHealSeg != null) {
                        // If super potion is available, we skip it
                        int superPotionMaxChecks = 10, superPotionCurrentCheck = 0;
                        while (superPotionCurrentCheck < superPotionMaxChecks && MarvinSegment.fromCue(BrowserManager.cues.get("SuperAvailable"), BHBot.browser) != null) {
                            BHBot.browser.clickInGame(656, 434);
                            BHBot.browser.readScreen(500);
                            superPotionCurrentCheck++;
                        }
                    }

                    // We check what revives are available, and we save the seg for future reuse
                    HashMap<Character, MarvinSegment> availablePotions = new HashMap<>();
                    availablePotions.put('1', MarvinSegment.fromCue(BrowserManager.cues.get("MinorAvailable"), BHBot.browser));
                    availablePotions.put('2', MarvinSegment.fromCue(BrowserManager.cues.get("AverageAvailable"), BHBot.browser));
                    availablePotions.put('3', MarvinSegment.fromCue(BrowserManager.cues.get("MajorAvailable"), BHBot.browser));

                    // No more potions are available
                    if (availablePotions.get('1') == null && availablePotions.get('2') == null && availablePotions.get('3') == null) {
                        BHBot.logger.warn("No potions are avilable, autoRevive well be temporary disabled!");
                        BHBot.settings.autoRevive = new ArrayList<>();
                        BHBot.scheduler.resetIdleTime(true);
                        return;
                    }

                    // We manage tank priority using the best potion we have
                    if (slotNum == (BHBot.settings.tankPosition) &&
                            ((state == State.Trials && BHBot.settings.tankPriority.contains("t")) ||
                                    (state == State.Gauntlet && BHBot.settings.tankPriority.contains("g")) ||
                                    (state == State.Raid && BHBot.settings.tankPriority.contains("r")) ||
                                    (state == State.Expedition && BHBot.settings.tankPriority.contains("e")))) {
                        for (char potion : "321".toCharArray()) {
                            seg = availablePotions.get(potion);
                            if (seg != null) {
                                BHBot.logger.autorevive("Handling tank priority (position: " + BHBot.settings.tankPosition + ") with " + potionTranslate.get(potion) + " revive.");
                                BHBot.browser.clickOnSeg(seg);
                                BHBot.browser.readScreen(SECOND);
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), SECOND, new Bounds(230, 320, 550, 410), BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                revived[BHBot.settings.tankPosition - 1] = true;
                                potionsUsed++;
                                BHBot.browser.readScreen(SECOND);
                                BHBot.scheduler.resetIdleTime(true);
                                break;
                            }
                        }
                    }

                    if (!revived[slotNum - 1]) { // This is only false when tank priory kicks in
                        for (char potion : BHBot.settings.potionOrder.toCharArray()) {
                            // BHBot.logger.info("Checking potion " + potion);
                            seg = availablePotions.get(potion);
                            if (seg != null) {
                                BHBot.logger.autorevive("Using " + potionTranslate.get(potion) + " revive on slot " + slotNum + ".");
                                BHBot.browser.clickOnSeg(seg);
                                BHBot.browser.readScreen(SECOND);
                                seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), SECOND, new Bounds(230, 320, 550, 410), BHBot.browser);
                                BHBot.browser.clickOnSeg(seg);
                                revived[slotNum - 1] = true;
                                potionsUsed++;
                                BHBot.browser.readScreen(SECOND);
                                BHBot.scheduler.resetIdleTime(true);
                                break;
                            }
                        }
                    }
                }
            }
        } else { // Impossible to find the potions button
            saveGameScreen("auto-revive-no-potions");
            BHBot.logger.autorevive("Impossible to find the potions button!");
        }

        // If the unit selection screen is still open, we need to close it
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("UnitSelect"), SECOND, BHBot.browser);
        if (seg != null) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
                BHBot.browser.readScreen(SECOND);
            }
        }

        inEncounterTimestamp = Misc.getTime() / 1000; //after reviving we update encounter timestamp as it wasn't updating from processDungeon

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoOff"), SECOND, BHBot.browser);
        if (seg != null) BHBot.browser.clickOnSeg(seg);
        BHBot.scheduler.resetIdleTime(true);
    }

    private void closeWorldBoss() {
        MarvinSegment seg;

        Misc.sleep(SECOND);
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 2 * SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        } else {
            BHBot.logger.error("first x Error returning to main screen from World Boss, restarting");
        }

        Misc.sleep(SECOND);
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 2 * SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        } else {
            BHBot.logger.error("yesgreen Error returning to main screen from World Boss, restarting");
        }

        Misc.sleep(SECOND);
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 2 * SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        } else {
            BHBot.logger.error("second x Error returning to main screen from World Boss, restarting");
        }

    }

    private void updateFamiliarCounter(String fam) {
        String familiarToUpdate = "";
        String updatedFamiliar = "";

        for (String fa : BHBot.settings.familiars) { //cycle through array
            String fString = fa.toUpperCase().split(" ")[0]; //case sensitive for a match so convert to upper case
            int currentCounter = Integer.parseInt(fa.split(" ")[1]); //set the bribe counter to an int
            if (fam.equals(fString)) { //when match is found from the function
                familiarToUpdate = fa; //write current status to String
                currentCounter--; // decrease the counter
                updatedFamiliar = (fString.toLowerCase() + " " + currentCounter); //update new string with familiar name and decrease counter
            }
        }

        try {
            // input the file content to the StringBuffer "input"
            BufferedReader file = new BufferedReader(new FileReader("settings.ini"));
            String line;
            StringBuilder inputBuffer = new StringBuilder();

            //print lines to string with linebreaks
            while ((line = file.readLine()) != null) {
                inputBuffer.append(line);
                inputBuffer.append(System.getProperty("line.separator"));
            }
            String inputStr = inputBuffer.toString(); //load lines to string
            file.close();

            //find containing string and update with the output string from the function above
            if (inputStr.contains(familiarToUpdate)) {
                inputStr = inputStr.replace(familiarToUpdate, updatedFamiliar);
            }

            // write the string from memory over the existing file
            FileOutputStream fileOut = new FileOutputStream("settings.ini");
            fileOut.write(inputStr.getBytes());
            fileOut.close();

            BHBot.settings.load();  //reload the new settings file so the counter will be updated for the next bribe

        } catch (Exception e) {
            System.out.println("Problem writing to settings file");
        }
    }

    private void settingsUpdate(String string, String updatedString) {

        try {
            // input the file content to the StringBuffer "input"
            BufferedReader file = new BufferedReader(new FileReader(Settings.configurationFile));
            String line;
            StringBuilder inputBuffer = new StringBuilder();

            //print lines to string with linebreaks
            while ((line = file.readLine()) != null) {
                inputBuffer.append(line);
                inputBuffer.append(System.getProperty("line.separator"));
            }
            String inputStr = inputBuffer.toString(); //load lines to string
            file.close();

            //find containing string and update with the output string from the function above
            if (inputStr.contains(string)) {
                inputStr = inputStr.replace(string, updatedString);
                BHBot.logger.info("Replaced '" + string + "' with '" + updatedString + "' in " + Settings.configurationFile);
            } else BHBot.logger.error("Error finding string: " + string);

            // write the string from memory over the existing file
            FileOutputStream fileOut = new FileOutputStream(Settings.configurationFile);
            fileOut.write(inputStr.getBytes());
            fileOut.close();

            BHBot.settings.load();  //reload the new settings file so the counter will be updated for the next bribe

        } catch (Exception e) {
            System.out.println("Problem writing to settings file");
        }
    }

    /**
     * @param z and integer with the desired zone.
     * @param d and integer with the desired dungeon.
     * @return null in case dungeon parameter is malformed (can even throw an exception)
     */
    private Point getDungeonIconPos(int z, int d) {
        if (z < 1 || z > 10) return null;
        if (d < 1 || d > 4) return null;

        switch (z) {
            case 1: // zone 1
                switch (d) {
                    case 1:
                        return new Point(240, 350);
                    case 2:
                        return new Point(580, 190);
                    case 3:
                        return new Point(660, 330);
                    case 4:
                        return new Point(410, 230);
                }
                break;
            case 2: // zone 2
                switch (d) {
                    case 1:
                        return new Point(215, 270);
                    case 2:
                        return new Point(550, 150);
                    case 3:
                        return new Point(515, 380);
                    case 4:
                        return new Point(400, 270);
                }
                break;
            case 3: // zone 3
                switch (d) {
                    case 1:
                        return new Point(145, 200);
                    case 2:
                        return new Point(430, 300);
                    case 3:
                        return new Point(565, 375);
                    case 4:
                        return new Point(570, 170);
                }
                break;
            case 4: // zone 4
                switch (d) {
                    case 1:
                        return new Point(300, 400);
                    case 2:
                        return new Point(260, 200);
                    case 3:
                        return new Point(650, 200);
                    case 4:
                        return new Point(400, 270);
                }
                break;
            case 5: // zone 5
                switch (d) {
                    case 1:
                        return new Point(150, 200);
                    case 2:
                        return new Point(410, 380);
                    case 3:
                        return new Point(630, 240);
                    case 4:
                        return new Point(550, 150);
                }
                break;
            case 6: // zone 6
                switch (d) {
                    case 1:
                        return new Point(150, 220);
                    case 2:
                        return new Point(500, 400);
                    case 3:
                        return new Point(550, 120);
                    case 4:
                        return new Point(400, 270);
                }
                break;
            case 7: // zone 7
                switch (d) {
                    case 1:
                        return new Point(215, 315);
                    case 2:
                        return new Point(570, 165);
                    case 3:
                        return new Point(400, 290);
                    case 4:
                        BHBot.logger.warn("Zone 7 only has 3 dungeons, falling back to z7d2");
                        return new Point(650, 400);
                }
                break;
            case 8: // zone 8
                switch (d) {
                    case 1:
                        return new Point(570, 170);
                    case 2:
                        return new Point(650, 390);
                    case 3:
                        return new Point(250, 370);
                    case 4:
                        BHBot.logger.warn("Zone 8 only has 3 dungeons, falling back to z8d2");
                        return new Point(570, 340);
                }
                break;
            case 9:
                switch (d) {
                    case 1:
                        return new Point(310, 165);
                    case 2:
                        return new Point(610, 190);
                    case 3:
                        return new Point(375, 415);
                    case 4:
                        BHBot.logger.warn("Zone 9 only has 3 dungeons, falling back to z9d2");
                        return new Point(610, 190);
                }
                break;
            case 10:
                switch (d) {
                    case 1:
                        return new Point(468, 389);
                    case 2:
                        return new Point(428, 261);
                    case 3:
                        return new Point(145, 200);
                    case 4:
                        return new Point(585, 167);
                }
        }


        return null;
    }

    /**
     * Function to return the name of the portal for console output
     */
    private String getExpeditionPortalName(int currentExpedition, String targetPortal) {
        if (currentExpedition > 5) {
            BHBot.logger.error("Unexpected expedition int in getExpeditionPortalName: " + currentExpedition);
            return null;
        }

        if (!"p1".equals(targetPortal) && !"p2".equals(targetPortal)
                && !"p3".equals(targetPortal) && !"p4".equals(targetPortal)) {
            BHBot.logger.error("Unexpected target portal in getExpeditionPortalName: " + targetPortal);
            return null;
        }

        switch (currentExpedition) {
            case 1: // Hallowed Dimension
                switch (targetPortal) {
                    case "p1":
                        return "Googarum's";
                    case "p2":
                        return "Svord's";
                    case "p3":
                        return "Twimbos";
                    case "p4":
                        return "X5-T34M's";
                    default:
                        return null;
                }
            case 2: // Inferno dimension
                switch (targetPortal) {
                    case "p1":
                        return "Raleib's";
                    case "p2":
                        return "Blemo's";
                    case "p3":
                        return "Gummy's";
                    case "p4":
                        return "Zarlocks";
                    default:
                        return null;
                }
            case 3:
                switch (targetPortal) {
                    case "p1":
                        return "Zorgo Crossing";
                    case "p2":
                        return "Yackerz Tundra";
                    case "p3":
                        return "Vionot Sewer";
                    case "p4":
                        return "Grampa Hef's Heart";
                    default:
                        return null;
                }
            case 4: // Idol dimension
                switch (targetPortal) {
                    case "p1":
                        return "Blublix";
                    case "p2":
                        return "Mowhi";
                    case "p3":
                        return "Wizbot";
                    case "p4":
                        return "Astamus";
                    default:
                        return null;
                }
            case 5: // Battle Bards!
                switch (targetPortal) {
                    case "p1":
                        return "Hero Fest";
                    case "p2":
                        return "Burning Fam";
                    case "p3":
                        return "Melvapaloozo";
                    case "p4":
                        return "Bitstock";
                    default:
                        return null;
                }
            default:
                return null;
        }
    }

    /**
     * @param targetPortal in standard format, e.g. "h4/i4".
     * @return null in case dungeon parameter is malformed (can even throw an exception)
     */
    private Point getExpeditionIconPos(int currentExpedition, String targetPortal) {
        if (targetPortal.length() != 2) {
            BHBot.logger.error("targetPortal length Mismatch in getExpeditionIconPos");
            return null;
        }

        String portalName = getExpeditionPortalName(currentExpedition, targetPortal);

        if (!"p1".equals(targetPortal) && !"p2".equals(targetPortal)
                && !"p3".equals(targetPortal) && !"p4".equals(targetPortal)) {
            BHBot.logger.error("Unexpected target portal in getExpeditionIconPos: " + targetPortal);
            return null;
        }

        int portalInt;
        switch (targetPortal) {
            case "p1":
                portalInt = 1;
                break;
            case "p2":
                portalInt = 2;
                break;
            case "p3":
                portalInt = 3;
                break;
            case "p4":
                portalInt = 4;
                break;
            default:
                portalInt = 0;
                break;
        }

        // we check for white border to understand if the portal is enabled
        Point[] portalCheck = new Point[4];
        Point[] portalPosition = new Point[4];
        Color[] colorCheck = new Color[4];
        boolean[] portalEnabled = new boolean[4];

        if (currentExpedition == 1) { // Hallowed

            portalCheck[0] = new Point(190, 146); //Googarum
            portalCheck[1] = new Point(484, 205); //Svord
            portalCheck[2] = new Point(328, 339); //Twimbo
            portalCheck[3] = new Point(641, 345); //X5-T34M

            portalPosition[0] = new Point(200, 200); //Googarum
            portalPosition[1] = new Point(520, 220); //Svord
            portalPosition[2] = new Point(360, 360); //Twimbo
            portalPosition[3] = new Point(650, 380); //X5-T34M

            colorCheck[0] = Color.WHITE;
            colorCheck[1] = Color.WHITE;
            colorCheck[2] = Color.WHITE;
            colorCheck[3] = Color.WHITE;
        } else if (currentExpedition == 2) { // Inferno
            portalCheck[0] = new Point(185, 206); // Raleib
            portalCheck[1] = new Point(570, 209); // Blemo
            portalCheck[2] = new Point(383, 395); // Gummy
            portalCheck[3] = new Point(381, 265); // Zarlock

            portalPosition[0] = new Point(200, 195); // Raleib
            portalPosition[1] = new Point(600, 195); // Blemo
            portalPosition[2] = new Point(420, 405); // Gummy
            portalPosition[3] = new Point(420, 270); // Zarlock

            colorCheck[0] = Color.WHITE;
            colorCheck[1] = Color.WHITE;
            colorCheck[2] = Color.WHITE;
            colorCheck[3] = Color.WHITE;
        } else if (currentExpedition == 3) { // Jammie
            portalCheck[0] = new Point(145, 187); // Zorgo
            portalCheck[1] = new Point(309, 289); // Yackerz
            portalCheck[2] = new Point(474, 343); // Vionot
            portalCheck[3] = new Point(621, 370); // Grampa

            portalPosition[0] = new Point(170, 200); // Zorgo
            portalPosition[1] = new Point(315, 260); // Yackerz
            portalPosition[2] = new Point(480, 360); // Vinot
            portalPosition[3] = new Point(635, 385); // Grampa

            colorCheck[0] = Color.WHITE;
            colorCheck[1] = Color.WHITE;
            colorCheck[2] = Color.WHITE;
            colorCheck[3] = Color.WHITE;
        } else if (currentExpedition == 4) { // Idol
            portalCheck[0] = new Point(370, 140); // Blublix
            portalCheck[1] = new Point(226, 369); // Mowhi
            portalCheck[2] = new Point(534, 350); // Wizbot
            portalCheck[3] = new Point(370, 324); // Astamus

            portalPosition[0] = new Point(400, 165); // Blublix
            portalPosition[1] = new Point(243, 385); // Mowhi
            portalPosition[2] = new Point(562, 375); // Wizbot
            portalPosition[3] = new Point(400, 318); // Astamus

            colorCheck[0] = Color.WHITE;
            colorCheck[1] = Color.WHITE;
            colorCheck[2] = Color.WHITE;
            colorCheck[3] = new Color(251, 201, 126);
        } else { // Battle Bards!
            portalCheck[0] = new Point(387, 152); // Hero Fest
            portalCheck[1] = new Point(253, 412); // Burning Fam
            portalCheck[2] = new Point(568, 418); // Melvapaloozo
            portalCheck[3] = new Point(435, 306); // Bitstock

            portalPosition[0] = new Point(402, 172); // Hero Fest
            portalPosition[1] = new Point(240, 371); // Burning Fam
            portalPosition[2] = new Point(565, 383); // Melvapaloozo
            portalPosition[3] = new Point(396, 315); // Bitstock

            colorCheck[0] = Color.WHITE;
            colorCheck[1] = Color.WHITE;
            colorCheck[2] = new Color(255, 254, 255); //Melvapaloozo is one bit off pure white for some reason
            colorCheck[3] = Color.WHITE;
        }

        // We check which of the portals are enabled
        for (int i = 0; i <= 3; i++) {
            Color col = new Color(BHBot.browser.getImg().getRGB(portalCheck[i].x, portalCheck[i].y));
            portalEnabled[i] = col.equals(colorCheck[i]);
        }

        if (portalEnabled[portalInt - 1]) {
            return portalPosition[portalInt - 1];
        }

        // If the desired portal is not enabled, we try to find the highest enabled one
        int i = 3;
        while (i >= 0) {
            if (portalEnabled[i]) {
                BHBot.logger.warn(portalName + " is not available! Falling back on p" + (i + 1) + "...");
                return portalPosition[i];
            }
            i--; //cycle down through 4 - 1 until we return an activated portal
        }

        return null;
    }

    /**
     * Check world boss inputs are valid
     **/
    private boolean checkWorldBossInput() {
        boolean failed = false;
        int passed = 0;

        String worldBossType = BHBot.settings.worldBossSettings.get(0);
        int worldBossTier = Integer.parseInt(BHBot.settings.worldBossSettings.get(2));

        //check name
        if ("o".equals(worldBossType) || "n".equals(worldBossType) || "m".equals(worldBossType)
                || "3".equals(worldBossType) || "b".equals(worldBossType)) {
            passed++;
        } else {
            BHBot.logger.error("Invalid world boss name, check settings file");
            failed = true;
        }

        //check tier
        if ("o".equals(worldBossType) || "n".equals(worldBossType)) {
            if (worldBossTier >= 3 && worldBossTier <= 9) {
                passed++;
            } else {
                BHBot.logger.error("Invalid world boss tier for Orlang or Nether, must be between 3 and 9");
                failed = true;
            }
        } else if ("m".equals(worldBossType) || "3".equals(worldBossType)) {
            if (worldBossTier >= 10 && worldBossTier <= 11) {
                passed++;
            } else {
                BHBot.logger.error("Invalid world boss tier for Melvin, 3xt3rmin4tion or Brimstone, must be T10 or higher.");
                failed = true;
            }
        } else if ("b".equals(worldBossType)) {
            if (worldBossTier == 11) {
                passed++;
            } else {
                BHBot.logger.error("Invalid world boss tier for Brimstone, must be T11.");
                failed = true;
            }
        }

        //warn user if timer is over 5 minutes
        if (BHBot.settings.worldBossTimer <= 300) {
            passed++;
        } else {
            BHBot.logger.warn("Warning: Timer longer than 5 minutes");
        }

        return !failed && passed == 3;


    }

    /**
     * Returns dungeon and difficulty level, e.g. 'z2d4 2'.
     */
    private String decideDungeonRandomly() {

        if ("3".equals(new SimpleDateFormat("u").format(new Date())) &&
                BHBot.settings.wednesdayDungeons.size() > 0) { // if its wednesday and wednesdayRaids is not empty
            return BHBot.settings.wednesdayDungeons.next();
        } else {
            return BHBot.settings.dungeons.next();
        }
    }

    /**
     * Returns raid type (1, 2 or 3) and difficulty level (1, 2 or 3, which correspond to normal, hard and heroic), e.g. '1 3'.
     */
    private String decideRaidRandomly() {
        if ("3".equals(new SimpleDateFormat("u").format(new Date())) &&
                BHBot.settings.wednesdayRaids.size() > 0) { // if its wednesday and wednesdayRaids is not empty
            return BHBot.settings.wednesdayRaids.next();
        } else {
            return BHBot.settings.raids.next();
        }
    }

    /**
     * Returns number of zone that is currently selected in the quest window (we need to be in the quest window for this to work).
     * Returns 0 in case zone could not be read (in case we are not in the quest window, for example).
     */
    private int readCurrentZone() {
        if (MarvinSegment.fromCue("Zone1", BHBot.browser) != null)
            return 1;
        else if (MarvinSegment.fromCue("Zone2", BHBot.browser) != null)
            return 2;
        else if (MarvinSegment.fromCue("Zone3", BHBot.browser) != null)
            return 3;
        else if (MarvinSegment.fromCue("Zone4", BHBot.browser) != null)
            return 4;
        else if (MarvinSegment.fromCue("Zone5", BHBot.browser) != null)
            return 5;
        else if (MarvinSegment.fromCue("Zone6", BHBot.browser) != null)
            return 6;
        else if (MarvinSegment.fromCue("Zone7", BHBot.browser) != null)
            return 7;
        else if (MarvinSegment.fromCue("Zone8", BHBot.browser) != null)
            return 8;
        else if (MarvinSegment.fromCue("Zone9", BHBot.browser) != null)
            return 9;
        else if (MarvinSegment.fromCue("Zone10", BHBot.browser) != null)
            return 10;
        else
            return 0;
    }

    void expeditionReadTest() {
        String expedition = BHBot.settings.expeditions.next();
        if (expedition != null) {
            expedition = expedition.split(" ")[0];
            BHBot.logger.info("Expedition chosen: " + expedition);
        }
    }

    /**
     * Note: world boss window must be open for this to work!
     * <p>
     * Returns false in case it failed.
     */
    private boolean handleWorldBossSelection(WorldBoss desiredWorldBoss) {

        MarvinSegment seg;

        // we refresh the screen
        BHBot.browser.readScreen(SECOND);

        int wbUnlocked = 0;
        int desiredWB = desiredWorldBoss.getNumber();

        // we get the grey dots on the raid selection popup
        List<MarvinSegment> wbDotsList = FindSubimage.findSubimage(BHBot.browser.getImg(), BrowserManager.cues.get("cueRaidLevelEmpty").im, 1.0, true, false, 0, 0, 0, 0);
        // we update the number of unlocked raids
        wbUnlocked += wbDotsList.size();

        // A  temporary variable to save the position of the current selected raid
        int selectedWBX1;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("RaidLevel"), BHBot.browser);
        if (seg != null) {
            wbUnlocked += 1;
            selectedWBX1 = seg.getX1();
            wbDotsList.add(seg);
        } else {
            BHBot.logger.error("Impossible to detect the currently selected green cue!");
            return false;
        }

        WorldBoss unlockedWB = WorldBoss.fromNumber(wbUnlocked);
        if (unlockedWB == null) {
            BHBot.logger.error("Unknown unlocked World Boss integer: " + wbUnlocked);
            return false;
        }

        BHBot.logger.debug("Detected: WB " + unlockedWB.getName() + " unlocked");

        if (wbUnlocked < desiredWB) {
            BHBot.logger.warn("World Boss selected in settings (" + desiredWorldBoss.getName() + ") is higher than world boss unlocked, running highest available (" + unlockedWB.getName() + ")");
            desiredWB = wbUnlocked;
        }

        // we sort the list of dots, using the x1 coordinate
        wbDotsList.sort(comparing(MarvinSegment::getX1));

        int selectedWB = 0;
        for (MarvinSegment raidDotSeg : wbDotsList) {
            selectedWB++;
            if (raidDotSeg.getX1() == selectedWBX1) break;
        }

        WorldBoss wbSelected = WorldBoss.fromNumber(selectedWB);
        if (wbSelected == null) {
            BHBot.logger.error("Unknown selected World Boss integer: " + wbUnlocked);
            return false;
        }

        BHBot.logger.debug("WB selected is " + wbSelected.getName());

        if (selectedWB == 0) { // an error!
            BHBot.logger.error("It was impossible to determine the currently selected raid!");
            return false;
        }

        if (selectedWB != desiredWB) {
            // we need to change the raid type!
            BHBot.logger.info("Changing from WB" + wbSelected.getName() + " to WB" + desiredWorldBoss.getName());
            // we click on the desired cue
            BHBot.browser.clickOnSeg(wbDotsList.get(desiredWB - 1));
        }

        return true;
    }

    /**
     * Note: raid window must be open for this to work!
     * <p>
     * Returns false in case it failed.
     */
    private boolean handleRaidSelection(int desiredRaid, int difficulty) {

        MarvinSegment seg;

        // we refresh the screen
        BHBot.browser.readScreen(SECOND);

        int raidUnlocked = 0;
        // we get the grey dots on the raid selection popup
        List<MarvinSegment> raidDotsList = FindSubimage.findSubimage(BHBot.browser.getImg(), BrowserManager.cues.get("cueRaidLevelEmpty").im, 1.0, true, false, 0, 0, 0, 0);
        // we update the number of unlocked raids
        raidUnlocked += raidDotsList.size();

        // Is only R1 unlocked?
        boolean onlyR1 = false;
        if (raidUnlocked == 0 && MarvinSegment.fromCue(BrowserManager.cues.get("Raid1Name"), BHBot.browser) != null) {
            raidUnlocked += 1;
            onlyR1 = true;
        }

        // A  temporary variable to save the position of the current selected raid
        int selectedRaidX1 = 0;

        // we look for the the currently selected raid, the green dot
        if (!onlyR1) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("RaidLevel"), BHBot.browser);
            if (seg != null) {
                raidUnlocked += 1;
                selectedRaidX1 = seg.getX1();
                raidDotsList.add(seg);
            } else {
                BHBot.logger.error("Impossible to detect the currently selected grey cue!");
                return false;
            }
        }

        BHBot.logger.debug("Detected: R" + raidUnlocked + " unlocked");

        if (raidUnlocked < desiredRaid) {
            BHBot.logger.warn("Raid selected in settings (R" + desiredRaid + ") is higher than raid level unlocked, running highest available (R" + raidUnlocked + ")");
            desiredRaid = raidUnlocked;
        }

        BHBot.logger.info("Attempting R" + desiredRaid + " " + (difficulty == 1 ? "Normal" : difficulty == 2 ? "Hard" : "Heroic"));

        // we sort the list of dots, using the x1 coordinate
        raidDotsList.sort(comparing(MarvinSegment::getX1));

        int selectedRaid = 0;
        if (!onlyR1) {
            for (MarvinSegment raidDotSeg : raidDotsList) {
                selectedRaid++;
                if (raidDotSeg.getX1() == selectedRaidX1) break;
            }
        } else {
            selectedRaid = 1;
        }

        BHBot.logger.debug("Raid selected is R" + selectedRaid);

        if (selectedRaid == 0) { // an error!
            BHBot.logger.error("It was impossible to determine the currently selected raid!");
            return false;
        }

        if (!onlyR1 && (selectedRaid != desiredRaid)) {
            // we need to change the raid type!
            BHBot.logger.info("Changing from R" + selectedRaid + " to R" + desiredRaid);
            // we click on the desired cue
            BHBot.browser.clickOnSeg(raidDotsList.get(desiredRaid - 1));
        }

        return true;
    }

    /**
     * Takes screenshot of current game and saves it to disk to a file with a given prefix (date will be added, and optionally a number at the end of file name).
     * In case of failure, it will just ignore the error.
     *
     * @return name of the path in which the screenshot has been saved (successfully or not)
     */
    String saveGameScreen(String prefix) {
        return saveGameScreen(prefix, null, BHBot.browser.takeScreenshot(true));
    }

    private String saveGameScreen(String prefix, BufferedImage img) {
        return saveGameScreen(prefix, null, img);
    }

    private String saveGameScreen(String prefix, String subFolder) {
        return saveGameScreen(prefix, subFolder, BHBot.browser.takeScreenshot(true));
    }

    private String saveGameScreen(String prefix, String subFolder, BufferedImage img) {

        // sub-folder logic management
        String screenshotPath = BHBot.screenshotPath;
        if (subFolder != null) {
            File subFolderPath = new File(BHBot.screenshotPath + subFolder + "/");
            if (!subFolderPath.exists()) {
                if (!subFolderPath.mkdir()) {
                    BHBot.logger.error("Impossible to create screenshot sub folder in " + subFolder);
                    return null;
                } else {
                    try {
                        BHBot.logger.info("Created screenshot sub-folder " + subFolderPath.getCanonicalPath());
                    } catch (IOException e) {
                        BHBot.logger.error("Error while getting Canonical Path for newly created screenshots sub-folder", e);
                    }
                }
            }
            screenshotPath += subFolder + "/";
        }

        Date date = new Date();
        String name = prefix + "_" + dateFormat.format(date) + ".png";
        int num = 0;
        File f = new File(screenshotPath + name);
        while (f.exists()) {
            num++;
            name = prefix + "_" + dateFormat.format(date) + "_" + num + ".png";
            f = new File(screenshotPath + name);
        }

        // save screen shot:
        try {
            ImageIO.write(img, "png", f);
        } catch (Exception e) {
            BHBot.logger.error("Impossible to take a screenshot!");
        }

        return f.getPath();
    }

    private void contributeFamiliarShoot(String shootName, FamiliarType familiarType) {

        HttpClient httpClient = HttpClients.custom().useSystemProperties().build();

        final HttpPost post = new HttpPost("https://script.google.com/macros/s/AKfycby-tCXZ6MHt_ZSUixCcNbYFjDuri6WvljomLgGy_m5lLZw1y5fZ/exec");

        // we generate a sub image with just the name of the familiar
        BHBot.browser.readScreen(SECOND);
        int familiarTxtColor;
        switch (familiarType) {
            case COMMON:
                familiarTxtColor = -6881668;
                break;
            case RARE:
                familiarTxtColor = -7168525;
                break;
            case EPIC:
                familiarTxtColor = -98436;
                break;
            case LEGENDARY:
                familiarTxtColor = -66048;
                break;
            case ERROR:
            default:
                familiarTxtColor = 0;
                break;
        }

        if (familiarTxtColor == 0) return;

        BufferedImage zoneImg = BHBot.browser.getImg().getSubimage(105, 60, 640, 105);

		/*File zoneImgTmp = new File("tmp-NAME-ZONE.png");
		try {
			ImageIO.write(zoneImg, "png", zoneImgTmp);
		} catch (IOException e) {
			BHBot.logger.error("", e);
		}*/

        int minX = zoneImg.getWidth();
        int minY = zoneImg.getHeight();
        int maxY = 0;
        int maxX = 0;

        int[][] pixelMatrix = Misc.convertTo2D(zoneImg);
        for (int y = 0; y < zoneImg.getHeight(); y++) {
            for (int x = 0; x < zoneImg.getWidth(); x++) {
                if (pixelMatrix[x][y] == familiarTxtColor) {
                    if (y < minY) minY = y;
                    if (x < minX) minX = x;
                    if (y > maxY) maxY = y;
                    if (x > maxX) maxX = x;
                } else {
                    zoneImg.setRGB(x, y, 0);
                }
            }

        }

        BufferedImage nameImg = zoneImg.getSubimage(minX, minY, maxX - minX, maxY - minY);
//		zoneImgTmp.delete();

        File nameImgFile = new File(shootName + "-ctb.png");
        try {
            ImageIO.write(nameImg, "png", nameImgFile);
        } catch (IOException e) {
            BHBot.logger.error("Error while creating contribution file", e);
        }

        MimetypesFileTypeMap ftm = new MimetypesFileTypeMap();
        ContentType ct = ContentType.create(ftm.getContentType(nameImgFile));

        List<NameValuePair> params = new ArrayList<>(3);
        params.add(new BasicNameValuePair("mimeType", ct.toString()));
        params.add(new BasicNameValuePair("name", nameImgFile.getName()));
        params.add(new BasicNameValuePair("data", Misc.encodeFileToBase64Binary(nameImgFile)));

        try {
            post.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            BHBot.logger.error("Error while encoding POST request in contribution", e);
        }

        try {
            httpClient.execute(post);
        } catch (IOException e) {
            BHBot.logger.error("Error while executing HTTP request in contribution", e);
        }

        if (!nameImgFile.delete()) {
            BHBot.logger.warn("Impossible to delete " + nameImgFile.getAbsolutePath());
        }

    }

    /**
     * Will detect and handle (close) in-game private message (from the current screen capture). Returns true in case PM has been handled.
     */
    private void handlePM() {
        if (MarvinSegment.fromCue(BrowserManager.cues.get("InGamePM"), BHBot.browser) != null) {
            MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: in-game PM window detected, but no close button found. Restarting...");
                restart(); //*** problem: after a call to this, it will return to the main loop. It should call "continue" inside the main loop or else there could be other exceptions!
                return;
            }

            try {
                String pmFileName = saveGameScreen("pm", "pm");
                if (BHBot.settings.enablePushover && BHBot.settings.poNotifyPM) {
                    if (pmFileName != null) {
                        sendPushOverMessage("New PM", "You've just received a new PM, check it out!", MessagePriority.NORMAL, new File(pmFileName));
                    } else {
                        sendPushOverMessage("New PM", "You've just received a new PM, check it out!", MessagePriority.NORMAL, null);
                    }
                }
                BHBot.browser.clickOnSeg(seg);
            } catch (Exception e) {
                // ignore it
            }
        }
    }

    /**
     * Handles popup that tells you that your team is not complete. Happens when some friend left you.
     * This method will attempt to click on "Auto" button to refill your team.
     * Note that this can happen in raid and GvG only, since in other games (PvP, Gauntlet/Trials) you can use only familiars.
     * In GvG, on the other hand, there is additional dialog possible (which is not possible in raid): "team not ordered" dialog.
     *
     * @return true in case emergency restart is needed.
     */
    private boolean handleTeamMalformedWarning() {

        // We look for the team text on top of the text pop-up
        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("Team"), SECOND * 3, new Bounds(330, 135, 480, 180), BHBot.browser);
        if (seg == null) {
            return false;
        }

        if (MarvinSegment.fromCue(BrowserManager.cues.get("TeamNotFull"), SECOND, BHBot.browser) != null || MarvinSegment.fromCue(BrowserManager.cues.get("TeamNotOrdered"), SECOND, BHBot.browser) != null) {
            BHBot.browser.readScreen(SECOND);
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("No"), 2 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: 'Team not full/ordered' window detected, but no 'No' button found. Restarting...");
                return true;
            }
            BHBot.browser.clickOnSeg(seg);
            BHBot.browser.readScreen();

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("AutoTeam"), 2 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: 'Team not full/ordered' window detected, but no 'Auto' button found. Restarting...");
                return true;
            }
            BHBot.browser.clickOnSeg(seg);

            BHBot.browser.readScreen();
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Accept"), 2 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: 'Team not full/ordered' window detected, but no 'Accept' button found. Restarting...");
                return true;
            }

            String message = "'Team not full/ordered' dialog detected and handled - team has been auto assigned!";

            if (BHBot.settings.enablePushover && BHBot.settings.poNotifyErrors) {
                String teamScreen = saveGameScreen("auto-team");
                File teamFile = teamScreen != null ? new File(teamScreen) : null;
                sendPushOverMessage("Team auto assigned", message, "siren", MessagePriority.NORMAL, teamFile);
                if (teamFile != null && !teamFile.delete())
                    BHBot.logger.warn("Impossible to delete tmp error img file for team auto assign");
            }

            BHBot.browser.clickOnSeg(seg);

            BHBot.logger.info(message);
        }

        return false; // all OK
    }

    private boolean handleGuildLeaveConfirm() {
        BHBot.browser.readScreen();
        if (MarvinSegment.fromCue(BrowserManager.cues.get("GuildLeaveConfirm"), SECOND * 3, BHBot.browser) != null) {
            Misc.sleep(500); // in case popup is still sliding downward
            BHBot.browser.readScreen();
            MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("YesGreen"), 10 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: 'Guild Leave Confirm' window detected, but no 'Yes' green button found. Restarting...");
                return true;
            }
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(2 * SECOND);

            BHBot.logger.info("'Guild Leave' dialog detected and handled!");
        }

        return false; // all ok
    }

    private Boolean handleDisabledBattles() {
        BHBot.browser.readScreen();
        if (MarvinSegment.fromCue(BrowserManager.cues.get("DisabledBattles"), SECOND * 3, BHBot.browser) != null) {
            Misc.sleep(500); // in case popup is still sliding downward
            BHBot.browser.readScreen();
            MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("Close"), 10 * SECOND, BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: 'Disabled battles' popup detected, but no 'Close' blue button found. Restarting...");
                return null;
            }
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(2 * SECOND);

            BHBot.logger.info("'Disabled battles' popup detected and handled!");
            return true;
        }

        return false; // all ok, battles are enabled
    }

    /**
     * Will check if "Not enough energy" popup is open. If it is, it will automatically close it and close all other windows
     * until it returns to the main screen.
     *
     * @return true in case popup was detected and closed.
     */
    private boolean handleNotEnoughEnergyPopup(@SuppressWarnings("SameParameterValue") int delay, State state) {
        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("NotEnoughEnergy"), delay, BHBot.browser);
        if (seg != null) {
            // we don't have enough energy!
            BHBot.logger.warn("Problem detected: insufficient energy to attempt " + state + ". Cancelling...");
            closePopupSecurely(BrowserManager.cues.get("NotEnoughEnergy"), BrowserManager.cues.get("No"));


            if (state.equals(State.WorldBoss)) {
                closePopupSecurely(BrowserManager.cues.get("WorldBossSummonTitle"), BrowserManager.cues.get("X"));

                closePopupSecurely(BrowserManager.cues.get("WorldBossTitle"), BrowserManager.cues.get("X"));
            } else {
                closePopupSecurely(BrowserManager.cues.get("AutoTeam"), BrowserManager.cues.get("X"));

                // if D4 close the dungeon info window, else close the char selection screen
                if (specialDungeon) {
                    seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                    if (seg != null)
                        BHBot.browser.clickOnSeg(seg);
                    specialDungeon = false;
                } else {
                    // close difficulty selection screen:
                    closePopupSecurely(BrowserManager.cues.get("Normal"), BrowserManager.cues.get("X"));
                }

                // close zone view window:
                closePopupSecurely(BrowserManager.cues.get("ZonesButton"), BrowserManager.cues.get("X"));
            }

            return true;
        } else {
            return false;
        }
    }

    /**
     * Will check if "Not enough tokens" popup is open. If it is, it will automatically close it and close all other windows
     * until it returns to the main screen.
     *
     * @return true in case popup was detected and closed.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean handleNotEnoughTokensPopup(boolean closeTeamWindow) {
        MarvinSegment seg = MarvinSegment.fromCue("NotEnoughTokens", BHBot.browser);

        if (seg != null) {
            BHBot.logger.warn("Not enough token popup detected! Closing trial window.");

            if (!closePopupSecurely(BrowserManager.cues.get("NotEnoughTokens"), BrowserManager.cues.get("No"))) {
                BHBot.logger.error("Impossible to close the 'Not Enough Tokens' pop-up window. Restarting");
                return false;
            }

            if (closeTeamWindow) {
                if (!closePopupSecurely(BrowserManager.cues.get("Accept"), BrowserManager.cues.get("X"))) {
                    BHBot.logger.error("Impossible to close the team window when no tokens are available. Restarting");
                    return false;
                }
            }

            if (!closePopupSecurely(BrowserManager.cues.get("TrialsOrGauntletWindow"), BrowserManager.cues.get("X"))) {
                BHBot.logger.error("Impossible to close the 'TrialsOrGauntletWindow' window. Restarting");
                return false;
            }
        }
        return true;
    }

    /**
     * This method will handle the success threshold based on the state
     *
     * @param state the State used to check the success threshold
     */
    private void handleSuccessThreshold(State state) {

        // We only handle Trials and Gautlets
        if (state != State.Gauntlet && state != State.Trials) return;

        BHBot.logger.debug("Victories in a row for " + state + " is " + counters.get(state).getVictoriesInARow());

        // We make sure that we have a setting for the current state
        if (BHBot.settings.successThreshold.containsKey(state.getShortcut())) {
            Map.Entry<Integer, Integer> successThreshold = BHBot.settings.successThreshold.get(state.getShortcut());
            int minimumVictories = successThreshold.getKey();
            int lvlIncrease = successThreshold.getValue();

            if (counters.get(state).getVictoriesInARow() >= minimumVictories) {
                if ("t".equals(state.getShortcut()) || "g".equals(state.getShortcut())) {
                    int newDifficulty;
                    String original, updated;

                    if ("t".equals(state.getShortcut())) {
                        newDifficulty = BHBot.settings.difficultyTrials + lvlIncrease;
                        original = "difficultyTrials " + BHBot.settings.difficultyTrials;
                        updated = "difficultyTrials " + newDifficulty;
                    } else { // Gauntlets
                        newDifficulty = BHBot.settings.difficultyGauntlet + lvlIncrease;
                        original = "difficultyGauntlet " + BHBot.settings.difficultyGauntlet;
                        updated = "difficultyGauntlet " + newDifficulty;
                    }

                    settingsUpdate(original, updated);
                }
            }
        }
    }

    /**
     * Reads number from given image.
     *
     * @return 0 in case of error.
     */
    private int readNumFromImg(BufferedImage im) {
        return readNumFromImg(im, "", new HashSet<>());
    }

    private int readNumFromImg(BufferedImage im, @SuppressWarnings("SameParameterValue") String numberPrefix, HashSet<Integer> intToSkip) {
        List<ScreenNum> nums = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            if (intToSkip.contains(i)) continue;
            List<MarvinSegment> list = FindSubimage.findSubimage(im, BrowserManager.cues.get(numberPrefix + "" + i).im, 1.0, true, false, 0, 0, 0, 0);
            //BHBot.logger.info("DEBUG difficulty detection: " + i + " - " + list.size());
            for (MarvinSegment s : list) {
                nums.add(new ScreenNum(i, s.x1));
            }
        }

        // order list horizontally:
        Collections.sort(nums);

        if (nums.size() == 0)
            return 0; // error

        int d = 0; // difficulty
        int f = 1; // factor
        for (int i = nums.size() - 1; i >= 0; i--) {
            d += nums.get(i).value * f;
            f *= 10;
        }

        return d;
    }

    private void makeImageBlackWhite(MarvinImage input, Color black, Color white) {
        makeImageBlackWhite(input, black, white, 254);
    }

    /**
     * @param input  The input image that will be converted in black and white scale
     * @param black  White color treshold
     * @param white  Black color treshold
     * @param custom Use the custom value to search for a specific RGB value if the numbers are not white
     *               E.G for invasion defeat screen the number colour is 64,64,64 in the background
     */
    private void makeImageBlackWhite(MarvinImage input, Color black, Color white, int custom) {
        int[] map = input.getIntColorArray();
        int white_rgb = white.getRGB();
        int black_rgb = black.getRGB();
        for (int i = 0; i < map.length; i++) {
            Color c = new Color(map[i], true);
            int r = c.getRed();
            int g = c.getGreen();
            int b = c.getBlue();
            int max = Misc.max(r, g, b);
            int min = Misc.min(r, g, b);
            //int diff = (max-r) + (max-g) + (max-b);
            int diff = max - min;
            if (diff >= 80 || (diff == 0 && max == custom)) { // it's a number color
                map[i] = white_rgb;
            } else { // it's a blackish background
                map[i] = black_rgb;
            }
        }
        input.setIntColorArray(map);
        input.update(); // must be called! Or else things won't work...
    }

    int detectDifficulty() {
        return detectDifficulty(BrowserManager.cues.get("Difficulty"));
    }

    /**
     * Detects selected difficulty in trials/gauntlet window. <br>
     * NOTE: Trials/gauntlet window must be open for this to work! <br>
     *
     * @return 0 in case of an error, or the selected difficulty level instead.
     */
    private int detectDifficulty(Cue difficulty) {
        BHBot.browser.readScreen(2 * SECOND); // note that sometimes the cue will be gray (disabled) since the game is fetching data from the server - in that case we'll have to wait a bit

        MarvinSegment seg = MarvinSegment.fromCue(difficulty, BHBot.browser);
        if (seg == null) {
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("DifficultyDisabled"), BHBot.browser);
            if (seg != null) { // game is still fetching data from the server... we must wait a bit!
                Misc.sleep(5 * SECOND);
                seg = MarvinSegment.fromCue(difficulty, 20 * SECOND, BHBot.browser);
            }
        }
        if (seg == null) {
            BHBot.logger.error("Error: unable to detect difficulty selection box!");
            saveGameScreen("early_error");
            return 0; // error
        }

        MarvinImage im = new MarvinImage(BHBot.browser.getImg().getSubimage(seg.x1 + 35, seg.y1 + 30, 55, 19));

        // make it white-gray (to facilitate cue recognition):
        makeImageBlackWhite(im, new Color(25, 25, 25), new Color(255, 255, 255));

        BufferedImage imb = im.getBufferedImage();

        return readNumFromImg(imb);
    }

    /* World boss reading and changing section */
    private int detectWorldBossTier() {

        BHBot.browser.readScreen(SECOND);
        MarvinSegment tierDropDown;
        int xOffset = 401, yOffset = 210, w = 21, h = 19;

        tierDropDown = MarvinSegment.fromCue("WorldBossTierDropDown", SECOND, BHBot.browser); // For tier drop down menu

        if (tierDropDown == null) {
            BHBot.logger.error("Error: unable to detect world boss difficulty selection box in detectWorldBossTier!");
            return 0; // error
        }

        MarvinImage im = new MarvinImage(BHBot.browser.getImg().getSubimage(xOffset, yOffset, w, h));

        // make it white-gray (to facilitate cue recognition):
        makeImageBlackWhite(im, new Color(25, 25, 25), new Color(255, 255, 255));

        BufferedImage imb = im.getBufferedImage();

        return readNumFromImg(imb);
    }

    private boolean changeWorldBossTier(int target) {
        MarvinSegment seg;
        BHBot.browser.readScreen(SECOND); //wait for screen to stabilize
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("WorldBossTierDropDown"), 2 * SECOND, BHBot.browser);

        if (seg == null) {
            BHBot.logger.error("Error: unable to detect world boss difficulty selection box in changeWorldBossTier!");
            saveGameScreen("early_error");
            return false;
        }

        BHBot.browser.clickOnSeg(seg);
        BHBot.browser.readScreen(2 * SECOND); //wait for screen to stabilize

        //get known screen position for difficulty screen selection
        if (target >= 5) { //top most
            BHBot.browser.readScreen();
            MarvinSegment up = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownUp"), SECOND, BHBot.browser);
            if (up != null) {
                BHBot.browser.clickOnSeg(up);
                BHBot.browser.clickOnSeg(up);
            }
        } else { //bottom most
            BHBot.browser.readScreen();
            MarvinSegment down = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownDown"), SECOND, BHBot.browser);
            if (down != null) {
                BHBot.browser.clickOnSeg(down);
                BHBot.browser.clickOnSeg(down);
            }
        }
        BHBot.browser.readScreen(SECOND); //wait for screen to stabilize
        Point diff = getDifficultyButtonXY(target);
        if (diff != null) {
            //noinspection SuspiciousNameCombination
            BHBot.browser.clickInGame(diff.y, diff.x);
        }
        return true;
    }

    private Point getDifficultyButtonXY(int target) {
        switch (target) {
            case 3:
            case 5: // top 5 buttons after we scroll to the top
                return new Point(410, 390);
            case 4: // bottom 2 buttons after we scroll to the bottom
            case 6:
                return new Point(350, 390);
            case 7:
                return new Point(290, 390);
            case 8:
            case 10:
                return new Point(230, 390);
            case 9:
            case 11:
                return new Point(170, 390);
        }
        return null;
    }

    private int detectWorldBossDifficulty() {
        BHBot.browser.readScreen();

        if (MarvinSegment.fromCue(BrowserManager.cues.get("WorldBossDifficultyNormal"), SECOND, BHBot.browser) != null) {
            return 1;
        } else if (MarvinSegment.fromCue(BrowserManager.cues.get("WorldBossDifficultyHard"), SECOND, BHBot.browser) != null) {
            return 2;
        } else if (MarvinSegment.fromCue(BrowserManager.cues.get("WorldBossDifficultyHeroic"), SECOND, BHBot.browser) != null) {
            return 3;
        } else return 0;
    }

    private void changeWorldBossDifficulty(int target) {

        BHBot.browser.readScreen(SECOND); //screen stabilising
        BHBot.browser.clickInGame(480, 300); //difficulty button
        BHBot.browser.readScreen(SECOND); //screen stabilising

        Cue difficultySelection;

        if (target == 1) {
            difficultySelection = BrowserManager.cues.get("cueWBSelectNormal");
        } else if (target == 2) {
            difficultySelection = BrowserManager.cues.get("cueWBSelectHard");
        } else if (target == 3) {
            difficultySelection = BrowserManager.cues.get("cueWBSelectHeroic");
        } else {
            BHBot.logger.error("Wrong target value in changeWorldBossDifficulty, defult to normal!");
            difficultySelection = BrowserManager.cues.get("cueWBSelectNormal");
        }

        MarvinSegment seg = MarvinSegment.fromCue(difficultySelection, SECOND * 2, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        } else {
            BHBot.logger.error("Impossible to detect desired difficulty in changeWorldBossDifficulty!");
            restart();
        }
    }

    /**
     * Changes difficulty level in trials/gauntlet window. <br>
     * Note: for this to work, trials/gauntlet window must be open!
     *
     * @return false in case of an error (unable to change difficulty).
     */
    boolean selectDifficulty(int oldDifficulty, int newDifficulty) {
        return selectDifficulty(oldDifficulty, newDifficulty, BrowserManager.cues.get("SelectDifficulty"), 1);
    }

    private boolean selectDifficulty(int oldDifficulty, int newDifficulty, Cue difficulty, int step) {
        if (oldDifficulty == newDifficulty)
            return true; // no change

        MarvinSegment seg = MarvinSegment.fromCue(difficulty, 2 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.error("Error: unable to detect 'select difficulty' button while trying to change difficulty level!");
            return false; // error
        }

        BHBot.browser.clickOnSeg(seg);

        BHBot.browser.readScreen(5 * SECOND);

        return selectDifficultyFromDropDown(newDifficulty, step);
    }

    /**
     * Internal routine. Difficulty drop down must be open for this to work!
     * Note that it closes the drop-down when it is done (except if an error occurred). However there is a close
     * animation and the caller must wait for it to finish.
     *
     * @return false in case of an error.
     */
    private boolean selectDifficultyFromDropDown(int newDifficulty, int step) {
        return selectDifficultyFromDropDown(newDifficulty, 0, step);
    }

    /**
     * Internal routine - do not use it manually! <br>
     *
     * @return false on error (caller must do restart() if he gets false as a result from this method)
     */
    private boolean selectDifficultyFromDropDown(int newDifficulty, int recursionDepth, int step) {
        // horizontal position of the 5 buttons:
        final int posx = 390;
        // vertical positions of the 5 buttons:
        final int[] posy = new int[]{170, 230, 290, 350, 410};

        if (recursionDepth > 3) {
            BHBot.logger.error("Error: Selecting difficulty level from the drop-down menu ran into an endless loop!");
            saveGameScreen("early_error");
            tryClosingWindow(); // clean up after our selves (ignoring any exception while doing it)
            return false;
        }

        MarvinSegment seg;

        MarvinImage subm = new MarvinImage(BHBot.browser.getImg().getSubimage(350, 150, 70, 35)); // the first (upper most) of the 5 buttons in the drop-down menu. Note that every while a "tier x" is written bellow it, so text is higher up (hence we need to scan a larger area)
        makeImageBlackWhite(subm, new Color(25, 25, 25), new Color(255, 255, 255));
        BufferedImage sub = subm.getBufferedImage();
        int num = readNumFromImg(sub);
//		BHBot.logger.info("num = " + Integer.toString(num));
        if (num == 0) {
            BHBot.logger.error("Error: unable to read difficulty level from a drop-down menu!");
            saveGameScreen("early_error");
            tryClosingWindow(); // clean up after our selves (ignoring any exception while doing it)
            return false;
        }

        int move = (newDifficulty - num) / step; // if negative, we have to move down (in dropdown/numbers), or else up
//		BHBot.logger.info("move = " + Integer.toString(move));

        if (move >= -4 && move <= 0) {
            // we have it on screen. Let's select it!
            BHBot.browser.clickInGame(posx, posy[Math.abs(move)]); // will auto-close the drop down (but it takes a second or so, since it's animated)
            return true;
        }

        // scroll the drop-down until we reach our position:
        // recursively select new difficulty
        //*** should we increase this time?
        if (move > 0) {
            // move up
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownUp"), BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: unable to detect up arrow in trials/gauntlet difficulty drop-down menu!");
                saveGameScreen("early_error");
                BHBot.browser.clickInGame(posx, posy[0]); // regardless of the error, click on the first selection in the drop-down, so that we don't need to re-scroll entire list next time we try!
                return false;
            }
            for (int i = 0; i < move; i++) {
                BHBot.browser.clickOnSeg(seg);
            }
            // OK, we should have a target value on screen now, in the first spot. Let's click it!
        } else {
            // move down
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownDown"), BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: unable to detect down arrow in trials/gauntlet difficulty drop-down menu!");
                saveGameScreen("early_error");
                BHBot.browser.clickInGame(posx, posy[0]); // regardless of the error, click on the first selection in the drop-down, so that we don't need to re-scroll entire list next time we try!
                return false;
            }
            int moves = Math.abs(move) - 4;
//			BHBot.logger.info("Scrolls to 60 = " + Integer.toString(moves));
            for (int i = 0; i < moves; i++) {
                BHBot.browser.clickOnSeg(seg);
            }
            // OK, we should have a target value on screen now, in the first spot. Let's click it!
        }
        BHBot.browser.readScreen(5 * SECOND); //*** should we increase this time?
        return selectDifficultyFromDropDown(newDifficulty, recursionDepth + 1, step); // recursively select new difficulty
    }

    /**
     * This method detects the select cost in PvP/GvG/Trials/Gauntlet window. <p>
     * <p>
     * Note: PvP cost has different position from GvG/Gauntlet/Trials. <br>
     * Note: PvP/GvG/Trials/Gauntlet window must be open in order for this to work!
     *
     * @return 0 in case of an error, or cost value in interval [1..5]
     */
    int detectCost() {
        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("Cost"), 15 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.error("Error: unable to detect cost selection box!");
            saveGameScreen("early_error");
            return 0; // error
        }

        // because the popup may still be sliding down and hence cue could be changing position, we try to read cost in a loop (until a certain timeout):
        int d;
        int counter = 0;
        boolean success = true;
        while (true) {
            MarvinImage im = new MarvinImage(BHBot.browser.getImg().getSubimage(seg.x1 + 2, seg.y1 + 20, 35, 24));
            makeImageBlackWhite(im, new Color(25, 25, 25), new Color(255, 255, 255));
            BufferedImage imb = im.getBufferedImage();
            d = readNumFromImg(imb);
            if (d != 0)
                break; // success

            counter++;
            if (counter > 10) {
                success = false;
                break;
            }
            Misc.sleep(SECOND); // sleep a bit in order for the popup to slide down
            BHBot.browser.readScreen();
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Cost"), BHBot.browser);
        }

        if (!success) {
            BHBot.logger.error("Error: unable to detect cost selection box value!");
            saveGameScreen("early_error");
            return 0;
        }

        return d;
    }

    /**
     * Changes cost in PvP, GvG, or Trials/Gauntlet window. <br>
     * Note: for this to work, PvP/GvG/Trials/Gauntlet window must be open!
     *
     * @return false in case of an error (unable to change cost).
     */
    boolean selectCost(int oldCost, int newCost) {
        if (oldCost == newCost)
            return true; // no change

        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("SelectCost"), 5 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.error("Error: unable to detect 'select cost' button while trying to change cost!");
            return false; // error
        }

        BHBot.browser.clickOnSeg(seg);

        MarvinSegment.fromCue("CostDropDown", 5 * SECOND, BHBot.browser); // wait for the cost selection popup window to open

        // horizontal position of the 5 buttons:
        final int posx = 390;
        // vertical positions of the 5 buttons:
        final int[] posy = new int[]{170, 230, 290, 350, 410};

        BHBot.browser.clickInGame(posx, posy[newCost - 1]); // will auto-close the drop down (but it takes a second or so, since it's animated)
        Misc.sleep(2 * SECOND);

        return true;
    }

    /**
     * Will try to click on "X" button of the currently open popup window. On error, it will ignore it. <br>
     * NOTE: This method does not re-read screen before (or after) cue detection!
     */
    private void tryClosingWindow() {
        tryClosingWindow(null);
    }

    /**
     * Will try to click on "X" button of the currently open popup window that is identified by the 'windowCue'. It will ignore any errors. <br>
     * NOTE: This method does not re-read screen before (or after) cue detection!
     */
    private void tryClosingWindow(Cue windowCue) {
        try {
            MarvinSegment seg;
            if (windowCue != null) {
                seg = MarvinSegment.fromCue(windowCue, BHBot.browser);
                if (seg == null)
                    return;
            }
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), BHBot.browser);
            if (seg != null)
                BHBot.browser.clickOnSeg(seg);
        } catch (Exception e) {
            BHBot.logger.error("Error in tryClosingWindow", e);
        }
    }

    /**
     * Will close the popup by clicking on the 'close' cue and checking that 'popup' cue is gone. It will repeat this operation
     * until either 'popup' cue is gone or timeout is reached. This method ensures that the popup is closed. Sometimes just clicking once
     * on the close button ('close' cue) doesn't work, since popup is still sliding down and we miss the button, this is why we need to
     * check if it is actually closed. This is what this method does.
     * <p>
     * Note that before entering into this method, caller had probably already detected the 'popup' cue (but not necessarily). <br>
     * Note: in case of failure, it will print it out.
     *
     * @return false in case it failed to close it (timed out).
     */
    private boolean closePopupSecurely(Cue popup, Cue close) {
        MarvinSegment seg1, seg2;
        int counter;
        seg1 = MarvinSegment.fromCue(close, BHBot.browser);
        seg2 = MarvinSegment.fromCue(popup, BHBot.browser);

        // make sure popup window is on the screen (or else wait until it appears):
        counter = 0;
        while (seg2 == null) {
            counter++;
            if (counter > 10) {
                BHBot.logger.error("Error: unable to close popup <" + popup.name + "> securely: popup cue not detected!");
                return false;
            }
            BHBot.browser.readScreen(SECOND);
            seg2 = MarvinSegment.fromCue(popup, BHBot.browser);
        }

        counter = 0;
        // there is no more popup window, so we're finished!
        while (seg2 != null) {
            if (seg1 != null)
                BHBot.browser.clickOnSeg(seg1);

            counter++;
            if (counter > 10) {
                BHBot.logger.error("Error: unable to close popup <" + popup.name + "> securely: either close button has not been detected or popup would not close!");
                return false;
            }

            BHBot.browser.readScreen(SECOND);
            seg1 = MarvinSegment.fromCue(close, BHBot.browser);
            seg2 = MarvinSegment.fromCue(popup, BHBot.browser);
        }

        return true;
    }

    /**
     * @return -1 on error
     */
    private int detectEquipmentFilterScrollerPos() {
        final int[] yScrollerPositions = {146, 164, 181, 199, 217, 235, 252, 270, 288, 306, 323, 341}; // top scroller positions

        MarvinSegment seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripScrollerTopPos"), 2 * SECOND, BHBot.browser);
        if (seg == null) {
            return -1;
        }
        int pos = seg.y1;

        return Misc.findClosestMatch(yScrollerPositions, pos);
    }

    /**
     * Will strip character down (as a preparation for the PvP battle) of items passed as parameters to this method.
     * Note that before calling this method, game must be in the main method!
     *
     * @param type which item type should we equip/unequip
     * @param dir  direction - either strip down or dress up
     */
    private void strip(EquipmentType type, StripDirection dir) {
        MarvinSegment seg;

        // click on the character menu button (it's a bottom-left button with your character image on it):
        BHBot.browser.clickInGame(55, 465);

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), 10 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.error("Error: unable to detect equipment filter button! Skipping...");
            return;
        }

        // now lets see if the right category is already selected:
        seg = MarvinSegment.fromCue(type.getCue(), 500, BHBot.browser);
        if (seg == null) {
            // OK we need to manually select the correct category!
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), BHBot.browser);
            BHBot.browser.clickOnSeg(seg);

            MarvinSegment.fromCue(BrowserManager.cues.get("StripItemsTitle"), 10 * SECOND, BHBot.browser); // waits until "Items" popup is detected
            BHBot.browser.readScreen(500); // to stabilize sliding popup a bit

            int scrollerPos = detectEquipmentFilterScrollerPos();
//			BHBot.logger.info("Scroller Pos = " + Integer.toString(scrollerPos));
            if (scrollerPos == -1) {
                BHBot.logger.warn("Problem detected: unable to detect scroller position in the character window (location #1)! Skipping strip down/up...");
                return;
            }

            int[] yButtonPositions = {170, 230, 290, 350, 410}; // center y positions of the 5 buttons
            int xButtonPosition = 390;

            if (scrollerPos < type.minPos()) {
                // we must scroll down!
                int move = type.minPos() - scrollerPos;
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownDown"), 5 * SECOND, BHBot.browser);
                for (int i = 0; i < move; i++) {
                    BHBot.browser.clickOnSeg(seg);
                    scrollerPos++;
                }
            } else { // bestIndex > type.maxPos
                // we must scroll up!
                int move = scrollerPos - type.minPos();
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownUp"), 5 * SECOND, BHBot.browser);
                for (int i = 0; i < move; i++) {
                    BHBot.browser.clickOnSeg(seg);
                    scrollerPos--;
                }
            }

            // make sure scroller is in correct position now:
            BHBot.browser.readScreen(500); // so that the scroller stabilizes a bit
            int newScrollerPos = detectEquipmentFilterScrollerPos();
            int counter = 0;
            while (newScrollerPos != scrollerPos) {
                if (counter > 3) {
                    BHBot.logger.warn("Problem detected: unable to adjust scroller position in the character window (scroller position: " + newScrollerPos + ", should be: " + scrollerPos + ")! Skipping strip down/up...");
                    return;
                }
                BHBot.browser.readScreen(SECOND);
                newScrollerPos = detectEquipmentFilterScrollerPos();
                counter++;
            }
            BHBot.browser.clickInGame(xButtonPosition, yButtonPositions[type.getButtonPos() - scrollerPos]);
            // clicking on the button will close the window automatically... we just need to wait a bit for it to close
            MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), 5 * SECOND, BHBot.browser); // we do this just in order to wait for the previous menu to reappear
        }

        waitForInventoryIconsToLoad(); // first of all, lets make sure that all icons are loaded

        // now deselect/select the strongest equipment in the menu:

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripEquipped"), 500, BHBot.browser); // if "E" icon is not found, that means that some other item is equipped or that no item is equipped
        boolean equipped = seg != null; // is strongest item equipped already?

        // position of top-left item (which is the strongest) is (490, 210)
        if (dir == StripDirection.StripDown) {
            BHBot.browser.clickInGame(490, 210);
        }
        if (!equipped) // in case item was not equipped, we must click on it twice, first time to equip it, second to unequip it. This could happen for example when we had some weaker item equipped (or no item equipped).
            BHBot.browser.clickInGame(490, 210);

        // OK, we're done, lets close the character menu window:
        closePopupSecurely(BrowserManager.cues.get("StripSelectorButton"), BrowserManager.cues.get("X"));
    }

    private void stripDown(List<String> striplist) {
        if (striplist.size() == 0)
            return;

        StringBuilder list = new StringBuilder();
        for (String type : striplist) {
            list.append(EquipmentType.letterToName(type)).append(", ");
        }
        list = new StringBuilder(list.substring(0, list.length() - 2));
        BHBot.logger.info("Stripping down for PvP/GVG (" + list + ")...");

        for (String type : striplist) {
            strip(EquipmentType.letterToType(type), StripDirection.StripDown);
        }
    }

    private void dressUp(List<String> striplist) {
        if (striplist.size() == 0)
            return;

        StringBuilder list = new StringBuilder();
        for (String type : striplist) {
            list.append(EquipmentType.letterToName(type)).append(", ");
        }
        list = new StringBuilder(list.substring(0, list.length() - 2));
        BHBot.logger.info("Dressing back up (" + list + ")...");

        // we reverse the order so that we have to make less clicks to dress up equipment
        Collections.reverse(striplist);
        for (String type : striplist) {
            strip(EquipmentType.letterToType(type), StripDirection.DressUp);
        }
        Collections.reverse(striplist);
    }

    /**
     * Daily collection of fishing baits!
     */
    private void handleFishingBaits() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Fishing"), SECOND * 5, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(SECOND); // we allow some seconds as maybe the reward popup is sliding down

            detectCharacterDialogAndHandleIt();

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("WeeklyRewards"), SECOND * 5, BHBot.browser);
            if (seg != null) {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
                if (seg != null) {
                    if ((BHBot.settings.screenshots.contains("a"))) {
                        saveGameScreen("fishing-baits");
                    }
                    BHBot.browser.clickOnSeg(seg);
                    BHBot.logger.info("Correctly collected fishing baits");
                    BHBot.browser.readScreen(SECOND * 2);
                } else {
                    BHBot.logger.error("Something weng wrong while collecting fishing baits, restarting...");
                    saveGameScreen("fishing-error-baits");
                    restart();
                }
            }

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), 5 * SECOND, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
                Misc.sleep(SECOND * 2);
                BHBot.browser.readScreen();
            } else {
                BHBot.logger.error("Something went wrong while closing the fishing dialog, restarting...");
                saveGameScreen("fishing-error-closing");
                restart();
            }

        } else {
            BHBot.logger.warn("Impossible to find the fishing button");
        }
        BHBot.browser.readScreen(SECOND * 2);
    }

    private boolean consumableReplaceCheck() {
        int coloursFound = 0;

        boolean foundGreen = false;
        boolean foundBlue = false;
        boolean foundRedFaded = false;
        boolean foundYellow = false;
        boolean foundRed = false;

        BHBot.browser.readScreen();
        BufferedImage consumableTest = BHBot.browser.getImg().getSubimage(258, 218, 311, 107);

        Color green = new Color(150, 254, 124);
        Color blue = new Color(146, 157, 243);
        Color redFaded = new Color(254, 127, 124); //faded red on 75% boosts
        Color yellow = new Color(254, 254, 0);
        Color red = new Color(254, 0, 71);

        for (int y = 0; y < consumableTest.getHeight(); y++) {
            for (int x = 0; x < consumableTest.getWidth(); x++) {
                if (!foundGreen && new Color(consumableTest.getRGB(x, y)).equals(green)) {
                    foundGreen = true;
                    coloursFound++;
                } else if (!foundBlue && new Color(consumableTest.getRGB(x, y)).equals(blue)) {
                    foundBlue = true;
                    coloursFound++;
                } else if (!foundRedFaded && new Color(consumableTest.getRGB(x, y)).equals(redFaded)) {
                    foundRedFaded = true;
                    coloursFound++;
                } else if (!foundYellow && new Color(consumableTest.getRGB(x, y)).equals(yellow)) {
                    foundYellow = true;
                    coloursFound++;
                } else if (!foundRed && new Color(consumableTest.getRGB(x, y)).equals(red)) {
                    foundRed = true;
                    coloursFound++;
                }

                if (coloursFound > 1) {
                    BHBot.logger.info("Replace Consumables text found, skipping");
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * We must be in main menu for this to work!
     */
    private void handleConsumables() {
        if (!BHBot.settings.autoConsume || BHBot.settings.consumables.size() == 0) // consumables management is turned off!
            return;

        MarvinSegment seg;

        boolean exp = MarvinSegment.fromCue(BrowserManager.cues.get("BonusExp"), BHBot.browser) != null;
        boolean item = MarvinSegment.fromCue(BrowserManager.cues.get("BonusItem"), BHBot.browser) != null;
        boolean speed = MarvinSegment.fromCue(BrowserManager.cues.get("BonusSpeed"), BHBot.browser) != null;
        boolean gold = MarvinSegment.fromCue(BrowserManager.cues.get("BonusGold"), BHBot.browser) != null;

        // Special consumables
        if (MarvinSegment.fromCue(BrowserManager.cues.get("ConsumablePumkgor"), BHBot.browser) != null || MarvinSegment.fromCue(BrowserManager.cues.get("ConsumableBroccoli"), BHBot.browser) != null
                || MarvinSegment.fromCue(BrowserManager.cues.get("ConsumableGreatFeast"), BHBot.browser) != null || MarvinSegment.fromCue(BrowserManager.cues.get("ConsumableGingernaut"), BHBot.browser) != null
                || MarvinSegment.fromCue(BrowserManager.cues.get("ConsumableCoco"), BHBot.browser) != null) {
            exp = true;
            item = true;
            speed = true;
            gold = true;
            // BHBot.logger.info("Special consumable detected, skipping all the rest...");
        }

        EnumSet<ConsumableType> duplicateConsumables = EnumSet.noneOf(ConsumableType.class); // here we store consumables that we wanted to consume now but we have detected they are already active, so we skipped them (used for error reporting)
        EnumSet<ConsumableType> consumables = EnumSet.noneOf(ConsumableType.class); // here we store consumables that we want to consume now
        for (String s : BHBot.settings.consumables)
            consumables.add(ConsumableType.getTypeFromName(s));
        //BHBot.logger.info("Testing for following consumables: " + Misc.listToString(consumables));

        if (exp) {
            consumables.remove(ConsumableType.EXP_MINOR);
            consumables.remove(ConsumableType.EXP_AVERAGE);
            consumables.remove(ConsumableType.EXP_MAJOR);
        }

        if (item) {
            consumables.remove(ConsumableType.ITEM_MINOR);
            consumables.remove(ConsumableType.ITEM_AVERAGE);
            consumables.remove(ConsumableType.ITEM_MAJOR);
        }

        if (speed) {
            consumables.remove(ConsumableType.SPEED_MINOR);
            consumables.remove(ConsumableType.SPEED_AVERAGE);
            consumables.remove(ConsumableType.SPEED_MAJOR);
        }

        if (gold) {
            consumables.remove(ConsumableType.GOLD_MINOR);
            consumables.remove(ConsumableType.GOLD_AVERAGE);
            consumables.remove(ConsumableType.GOLD_MAJOR);
        }

        // so now we have only those consumables in the 'consumables' list that we actually need to consume right now!

        if (consumables.isEmpty()) // we don't need to do anything!
            return;

        // OK, try to consume some consumables!
        BHBot.logger.info("Trying to consume some consumables (" + Misc.listToString(consumables) + ")...");

        // click on the character menu button (it's a bottom-left button with your character image on it):
        BHBot.browser.clickInGame(55, 465);

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), 15 * SECOND, BHBot.browser);
        if (seg == null) {
            BHBot.logger.warn("Error: unable to detect equipment filter button! Skipping...");
            return;
        }

        // now lets select the <Consumables> category (if it is not already selected):
        seg = MarvinSegment.fromCue(BrowserManager.cues.get("FilterConsumables"), 500, BHBot.browser);
        if (seg == null) { // if not, right category (<Consumables>) is already selected!
            // OK we need to manually select the <Consumables> category!
            seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), BHBot.browser);
            BHBot.browser.clickOnSeg(seg);

            MarvinSegment.fromCue(BrowserManager.cues.get("StripItemsTitle"), 10 * SECOND, BHBot.browser); // waits until "Items" popup is detected
            BHBot.browser.readScreen(500); // to stabilize sliding popup a bit

            int scrollerPos = detectEquipmentFilterScrollerPos();
            if (scrollerPos == -1) {
                BHBot.logger.warn("Problem detected: unable to detect scroller position in the character window (location #1)! Skipping consumption of consumables...");
                return;
            }

            int[] yButtonPositions = {170, 230, 290, 350, 410}; // center y positions of the 5 buttons
            int xButtonPosition = 390;

            if (scrollerPos != 0) {
                // we must scroll up!
                int move = scrollerPos;
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownUp"), 5 * SECOND, BHBot.browser);
                for (int i = 0; i < move; i++) {
                    BHBot.browser.clickOnSeg(seg);
                    scrollerPos--;
                }
            }

            // make sure scroller is in correct position now:
            BHBot.browser.readScreen(2000); // so that the scroller stabilizes a bit //Quick Fix slow down
            int newScrollerPos = detectEquipmentFilterScrollerPos();
            int counter = 0;
            while (newScrollerPos != scrollerPos) {
                if (counter > 3) {
                    BHBot.logger.warn("Problem detected: unable to adjust scroller position in the character window (scroller position: " + newScrollerPos + ", should be: " + scrollerPos + ")! Skipping consumption of consumables...");
                    return;
                }
                BHBot.browser.readScreen(SECOND);
                newScrollerPos = detectEquipmentFilterScrollerPos();
                counter++;
            }
            BHBot.browser.clickInGame(xButtonPosition, yButtonPositions[1]);
            // clicking on the button will close the window automatically... we just need to wait a bit for it to close
            MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), 5 * SECOND, BHBot.browser); // we do this just in order to wait for the previous menu to reappear
        }

        // now consume the consumable(s):

        BHBot.browser.readScreen(500); // to stabilize window a bit
        Bounds bounds = new Bounds(450, 165, 670, 460); // detection area (where consumables icons are visible)

        while (!consumables.isEmpty()) {
            waitForInventoryIconsToLoad(); // first of all, lets make sure that all icons are loaded
            for (Iterator<ConsumableType> i = consumables.iterator(); i.hasNext(); ) {
                ConsumableType c = i.next();
                seg = MarvinSegment.fromCue(new Cue(c.getInventoryCue(), bounds), BHBot.browser);
                if (seg != null) {
                    // OK we found the consumable icon! Lets click it...
                    BHBot.browser.clickOnSeg(seg);
                    MarvinSegment.fromCue(BrowserManager.cues.get("ConsumableTitle"), 5 * SECOND, BHBot.browser); // wait for the consumable popup window to appear
                    BHBot.browser.readScreen(500); // wait for sliding popup to stabilize a bit

                    /*
                     *  Measure distance between "Consumable" (popup title) and "Yes" (green yes button).
                     *  This seems to be the safest way to distinguish the two window types. Because text
                     *  inside windows change and sometimes letters are wider apart and sometimes no, so it
                     *  is not possible to detect cue like "replace" wording, or any other (I've tried that
                     *  and failed).
                     */

                    if (!consumableReplaceCheck()) {
                        // don't consume the consumable... it's already in use!
                        BHBot.logger.warn("\"Replace consumable\" dialog detected for (" + c.getName() + "). Skipping...");
                        duplicateConsumables.add(c);
                        closePopupSecurely(BrowserManager.cues.get("ConsumableTitle"), BrowserManager.cues.get("No"));
                    } else {
                        // consume the consumable:
                        closePopupSecurely(BrowserManager.cues.get("ConsumableTitle"), BrowserManager.cues.get("YesGreen"));
                    }
                    MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), 5 * SECOND, BHBot.browser); // we do this just in order to wait for the previous menu to reappear
                    i.remove();
                }
            }

            if (!consumables.isEmpty()) {
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("ScrollerAtBottom"), 500, BHBot.browser);
                if (seg != null)
                    break; // there is nothing we can do anymore... we've scrolled to the bottom and haven't found the icon(s). We obviously don't have the required consumable(s)!

                // lets scroll down:
                seg = MarvinSegment.fromCue(BrowserManager.cues.get("DropDownDown"), 5 * SECOND, BHBot.browser);
                for (int i = 0; i < 4; i++) { //the menu has 4 rows so we move to the next four rows and check again
                    BHBot.browser.clickOnSeg(seg);
                }

                BHBot.browser.readScreen(SECOND); // so that the scroller stabilizes a bit
            }
        }

        // OK, we're done, lets close the character menu window:
        boolean result = closePopupSecurely(BrowserManager.cues.get("StripSelectorButton"), BrowserManager.cues.get("X"));
        if (!result) {
            BHBot.logger.warn("Done. Error detected while trying to close character window. Ignoring...");
            return;
        }

        if (!consumables.isEmpty()) {
            BHBot.logger.warn("Some consumables were not found (out of stock?) so were not consumed. These are: " + Misc.listToString(consumables) + ".");

            for (ConsumableType c : consumables) {
                BHBot.settings.consumables.remove(c.getName());
            }

            BHBot.logger.warn("The following consumables have been removed from auto-consume list: " + Misc.listToString(consumables) + ". In order to reactivate them, reload your settings.ini file using 'reload' command.");
        } else {
            if (!duplicateConsumables.isEmpty())
                BHBot.logger.info("Done. Some of the consumables have been skipped since they are already in use: " + Misc.listToString(duplicateConsumables));
            else
                BHBot.logger.info("Done. Desired consumables have been successfully consumed.");
        }
    }

    /**
     * Will make sure all the icons in the inventory have been loaded.
     */
    private void waitForInventoryIconsToLoad() {
        Bounds bounds = new Bounds(450, 165, 670, 460); // detection area (where inventory icons are visible)
        MarvinSegment seg;
        Cue cue = new Cue(BrowserManager.cues.get("LoadingInventoryIcon"), bounds);

        int counter = 0;
        seg = MarvinSegment.fromCue(cue, BHBot.browser);
        while (seg != null) {
            BHBot.browser.readScreen(SECOND);

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("StripSelectorButton"), BHBot.browser);
            if (seg == null) {
                BHBot.logger.error("Error: while detecting possible loading of inventory icons, inventory cue has not been detected! Ignoring...");
                return;
            }

            seg = MarvinSegment.fromCue(cue, BHBot.browser);
            counter++;
            if (counter > 100) {
                BHBot.logger.error("Error: loading of icons has been detected in the inventory screen, but it didn't finish in time. Ignoring...");
                return;
            }
        }
    }

    /**
     * Will reset readout timers.
     */
    void resetTimers() {
        timeLastExpBadgesCheck = 0;
        timeLastInvBadgesCheck = 0;
        timeLastGVGBadgesCheck = 0;
        timeLastEnergyCheck = 0;
        timeLastShardsCheck = 0;
        timeLastTicketsCheck = 0;
        timeLastTrialsTokensCheck = 0;
        timeLastGauntletTokensCheck = 0;
        timeLastBonusCheck = 0;
        timeLastFishingCheck = 0;
        timeLastFishingBaitsCheck = 0;
    }

    /* This will only reset timers for activities we still have resources to run */
    /* This saves cycling through the list of all activities to run every time we finish one */
    /* It's also useful for other related settings to be reset on activity finish */
    private void resetAppropriateTimers() {
        startTimeCheck = false;
        specialDungeon = false;
        potionsUsed = 0;

        /*
            In this section we check if we are able to run the activity again and if so reset the timer to 0
            else we wait for the standard timer until we check again
         */

        if (((globalShards - 1) >= BHBot.settings.minShards) && state == State.Raid) {
            timeLastShardsCheck = 0;
        }

        if (((globalBadges - BHBot.settings.costExpedition) >= BHBot.settings.costExpedition) && state == State.Expedition) {
            timeLastExpBadgesCheck = 0;
        }

        if (((globalBadges - BHBot.settings.costInvasion) >= BHBot.settings.costInvasion) && state == State.Invasion) {
            timeLastInvBadgesCheck = 0;
        }

        if (((globalBadges - BHBot.settings.costGVG) >= BHBot.settings.costGVG && state == State.GVG)) {
            timeLastGVGBadgesCheck = 0;
        }

        if (((globalEnergy - 10) >= BHBot.settings.minEnergyPercentage) && state == State.Dungeon) {
            timeLastEnergyCheck = 0;
        }

        if (((globalEnergy - 10) >= BHBot.settings.minEnergyPercentage) && state == State.WorldBoss) {
            timeLastEnergyCheck = 0;
        }

        if (((globalTickets - BHBot.settings.costPVP) >= BHBot.settings.costPVP) && state == State.PVP) {
            timeLastTicketsCheck = 0;
        }

        if (((globalTokens - BHBot.settings.costTrials) >= BHBot.settings.costTrials) && state == State.Trials) {
            timeLastTrialsTokensCheck = 0;
        }

        if (((globalTokens - BHBot.settings.costGauntlet) >= BHBot.settings.costGauntlet && state == State.Gauntlet)) {
            timeLastGauntletTokensCheck = 0;
        }
    }

    private void resetRevives() {
        revived[0] = false;
        revived[1] = false;
        revived[2] = false;
        revived[3] = false;
        revived[4] = false;
    }

    void sendPushOverMessage(String title, String msg, MessagePriority priority, File attachment) {
        sendPushOverMessage(title, msg, "pushover", priority, attachment);
    }

    private void sendPushOverMessage(String title, String msg, @SuppressWarnings("SameParameterValue") String sound) {
        sendPushOverMessage(title, msg, sound, MessagePriority.NORMAL, null);
    }

    private void sendPushOverMessage(String title, String msg, String sound, MessagePriority priority, File attachment) {
        if (BHBot.settings.enablePushover) {

            if (!"".equals(BHBot.settings.username) && !"yourusername".equals(BHBot.settings.username) ) {
                title = "[" + BHBot.settings.username + "] " + title;
            }

            try {
                BHBot.poClient.pushMessage(
                        PushoverMessage.builderWithApiToken(BHBot.settings.poAppToken)
                                .setUserId(BHBot.settings.poUserToken)
                                .setTitle(title)
                                .setMessage(msg)
                                .setPriority(priority)
                                .setSound(sound)
                                .setImage(attachment)
                                .build());
            } catch (PushoverException e) {
                BHBot.logger.error("Error while sending Pushover message", e);
            }
        }
    }

    private Bounds opponentSelector(int opponent) {

        if (BHBot.settings.pvpOpponent < 1 || BHBot.settings.pvpOpponent > 4) {
            //if setting outside 1-4th opponents we default to 1st
            BHBot.logger.warn("pvpOpponent must be between 1 and 4, defaulting to first opponent");
            BHBot.settings.pvpOpponent = 1;
            return new Bounds(544, 188, 661, 225); //1st opponent
        }

        if (BHBot.settings.gvgOpponent < 1 || BHBot.settings.gvgOpponent > 4) {
            //if setting outside 1-4th opponents we default to 1st
            BHBot.logger.warn("gvgOpponent must be between 1 and 4, defaulting to first opponent");
            BHBot.settings.gvgOpponent = 1;
            return new Bounds(544, 188, 661, 225); //1st opponent
        }

        switch (opponent) {
            case 1:
                return new Bounds(545, 188, 660, 225); //1st opponent
            case 2:
                return new Bounds(545, 243, 660, 279); //2nd opponent
            case 3:
                return new Bounds(544, 296, 660, 335); //1st opponent
            case 4:
                return new Bounds(544, 351, 660, 388); //1st opponent
        }
        return null;
    }

    void softReset() {
        state = State.Main;
        resetTimers();
    }

    private void handleFishing() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Fishing"), SECOND * 5, BHBot.browser);
        if (seg != null) {

            //we make sure that the window is visible
            BHBot.browser.showBrowser();

            BHBot.browser.clickOnSeg(seg);
            Misc.sleep(SECOND); // we allow some seconds as maybe the reward popup is sliding down

            detectCharacterDialogAndHandleIt();

            int fishingTime = 10 + (BHBot.settings.baitAmount * 15); //pause for around 15 seconds per bait used, plus 10 seconds buffer

            BHBot.browser.readScreen();

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Play"), SECOND * 5, BHBot.browser);
            if (seg != null) {
                BHBot.browser.clickOnSeg(seg);
            }

            seg = MarvinSegment.fromCue(BrowserManager.cues.get("Start"), SECOND * 20, BHBot.browser);
            if (seg != null) {
                try {
                    BHBot.logger.info("Pausing for " + fishingTime + " seconds to fish");
                    BHBot.scheduler.pause();

                    Process fisher = Runtime.getRuntime().exec("cmd /k \"cd DIRECTORY & java -jar bh-fisher.jar\" " + BHBot.settings.baitAmount);
                    if (!fisher.waitFor(fishingTime, TimeUnit.SECONDS)) { //run and wait for fishingTime seconds
                        BHBot.scheduler.resume();
                    }

                } catch (IOException | InterruptedException ex) {
                    BHBot.logger.error("Can't start bh-fisher.jar", ex);
                }

            } else BHBot.logger.info("start not found");

            if (!closeFishingSafely()) {
                BHBot.logger.error("Error closing fishing, restarting..");
                restart();
            }

            BHBot.browser.readScreen(SECOND);
            enterGuildHall();

            if (BHBot.settings.hideWindowOnRestart) BHBot.browser.hideBrowser();
        }

    }

    private boolean closeFishingSafely() {
        MarvinSegment seg;
        BHBot.browser.readScreen();

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Trade"), SECOND * 3, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        }

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("X"), SECOND * 3, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        }

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("FishingClose"), 3 * SECOND, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        }

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("GuildButton"), SECOND * 5, BHBot.browser);
        //else not
        return seg != null; //if we can see the guild button we are successful

    }

    private void enterGuildHall() {
        MarvinSegment seg;

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("GuildButton"), SECOND * 5, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        }

        seg = MarvinSegment.fromCue(BrowserManager.cues.get("Hall"), SECOND * 5, BHBot.browser);
        if (seg != null) {
            BHBot.browser.clickOnSeg(seg);
        }
    }

    private void handleLoot() {
        MarvinSegment seg;
        BufferedImage victoryPopUpImg = BHBot.browser.getImg();
        boolean itemFound = false;

        if (BHBot.settings.enablePushover) {
            BHBot.browser.readScreen();
            String droppedItemMessage;
            String tierName = "";
            Bounds victoryDropArea = new Bounds(100, 160, 630, 420);

            //linkedHashMap so we iterate from mythic > heroic
            LinkedHashMap<String, Cue> itemTier = new LinkedHashMap<>();
            itemTier.put("m", BrowserManager.cues.get("ItemMyt"));
            itemTier.put("s", BrowserManager.cues.get("ItemSet"));
            itemTier.put("l", BrowserManager.cues.get("ItemLeg"));
            itemTier.put("h", BrowserManager.cues.get("ItemHer"));

            for (Map.Entry<String, Cue> item : itemTier.entrySet()) {
                if (BHBot.settings.poNotifyDrop.contains(item.getKey())) {
                seg = MarvinSegment.fromCue(item.getValue(), 0, victoryDropArea, BHBot.browser);
                    if (seg != null && !itemFound) {
                        //so we don't get legendary crafting materials in raids triggering handleLoot
                        if ((item.getKey().equals("l")) && (restrictedCues(getSegBounds(seg)))) return;
                        //this is so we only get Coins, Crafting Materials and Schematics for heroic items
                        if (item.getKey().equals("h") && (!allowedCues(getSegBounds(seg)))) return;
                        if (state != State.Raid && state != State.Dungeon && state != State.Expedition && state != State.Trials) {
                            //the window moves too fast in these events to mouseOver
                            BHBot.browser.moveMouseToPos(seg.getCenterX(), seg.getCenterY());
                            BHBot.browser.readScreen();
                            victoryPopUpImg = BHBot.browser.getImg();
                            BHBot.browser.moveMouseAway();
                        }
                        itemFound = true; //so we only screenshot the highest tier found, and not equipped items on the hover popup
                        tierName = getItemTier(item.getKey());
                    }
                }
            }

            if (itemFound) {
                droppedItemMessage = tierName + " item dropped!";
                BHBot.logger.debug(droppedItemMessage);
                if (BHBot.settings.victoryScreenshot) {
                    saveGameScreen(state + "-" + tierName.toLowerCase(), "loot", victoryPopUpImg);
                }
                String victoryScreenName = saveGameScreen("victory-screen", victoryPopUpImg);
                File victoryScreenFile = victoryScreenName != null ? new File(victoryScreenName) : null;
                sendPushOverMessage(tierName + " item drop in " + state, droppedItemMessage, "magic", MessagePriority.NORMAL, victoryScreenFile);
                if (victoryScreenFile != null && !victoryScreenFile.delete())
                    BHBot.logger.warn("Impossible to delete tmp img file for victory drop.");
            }
        }

    }

    private String getItemTier(String tier) {
        switch (tier) {
            case "m":
                return "Mythic";
            case "s":
                return "Set";
            case "l":
                return "Legendary";
            case "h":
                return "Heroic";
            default:
                return "unknown_tier";
        }
    }

    public enum State {
        Dungeon("Dungeon", "d"),
        Expedition("Expedition", "e"),
        Gauntlet("Gauntlet", "g"),
        GVG("GVG", "v"),
        Invasion("Invasion", "i"),
        Loading("Loading..."),
        Main("Main screen"),
        PVP("PVP", "p"),
        Raid("Raid", "r"),
        Trials("Trials", "t"),
        UnidentifiedDungeon("Unidentified dungeon", "ud"), // this one is used when we log in and we get a "You were recently disconnected from a dungeon. Do you want to continue the dungeon?" window
        WorldBoss("World Boss", "w");

        private String name;
        private String shortcut;

        State(String name) {
            this.name = name;
            this.shortcut = null;
        }

        State(String name, String shortcut) {
            this.name = name;
            this.shortcut = shortcut;
        }

        public String getName() {
            return name;
        }

        public String getShortcut() {
            return shortcut;
        }

        public String getNameFromShortcut(String shortcut) {
            for (State state : State.values())
                if (state.shortcut != null && state.shortcut.equals(shortcut))
                    return state.name;
            return null;
        }
    }

    public enum PersuationType {
        DECLINE("Decline"),
        PERSUADE("Persuasion"),
        BRIBE("Bribe");

        private final String name;

        PersuationType(String name) {
            this.name = name;
        }

        public String toString() {
            return this.name;
        }

    }

    public enum FamiliarType {
        ERROR("Error", 0),
        COMMON("Common", 1),
        RARE("Rare", 2),
        EPIC("Epic", 3),
        LEGENDARY("Legendary", 4);

        private final String name;
        private final int type;

        FamiliarType(String name, int type) {
            this.name = name;
            this.type = type;
        }

        public int getValue() {
            return this.type;
        }

        public String toString() {
            return this.name;
        }

    }

    /**
     * Events that use badges as "fuel".
     */
    public enum BadgeEvent {
        None,
        GVG,
        Expedition,
        Invasion
    }

    public enum EquipmentType {
        Mainhand("StripTypeMainhand"),
        Offhand("StripTypeOffhand"),
        Head("StripTypeHead"),
        Body("StripTypeBody"),
        Neck("StripTypeNeck"),
        Ring("StripTypeRing");

        private String cueName;

        EquipmentType(String cueName) {
            this.cueName = cueName;
        }

        public static String letterToName(String s) {
            switch (s) {
                case "m":
                    return "mainhand";
                case "o":
                    return "offhand";
                case "h":
                    return "head";
                case "b":
                    return "body";
                case "n":
                    return "neck";
                case "r":
                    return "ring";
                default:
                    return "unknown_item";
            }
        }

        public static EquipmentType letterToType(String s) {
            switch (s) {
                case "m":
                    return Mainhand;
                case "o":
                    return Offhand;
                case "h":
                    return Head;
                case "b":
                    return Body;
                case "n":
                    return Neck;
                case "r":
                    return Ring;
                default:
                    return null; // should not happen!
            }
        }

//		public int maxPos() {
////			return Math.min(6 + ordinal(), 10);
////		}

        /**
         * Returns equipment filter button cue (it's title cue actually)
         */
        public Cue getCue() {
            return BrowserManager.cues.get(cueName);
        }

        public int minPos() {
            return 4 + ordinal();
        }

        public int getButtonPos() {
            return 8 + ordinal();
        }
    }

    public enum StripDirection {
        StripDown,
        DressUp
    }

    private enum ConsumableType {
        EXP_MINOR("exp_minor", "ConsumableExpMinor"), // experience tome
        EXP_AVERAGE("exp_average", "ConsumableExpAverage"),
        EXP_MAJOR("exp_major", "ConsumableExpMajor"),

        ITEM_MINOR("item_minor", "ConsumableItemMinor"), // item find scroll
        ITEM_AVERAGE("item_average", "ConsumableItemAverage"),
        ITEM_MAJOR("item_major", "ConsumableItemMajor"),

        GOLD_MINOR("gold_minor", "ConsumableGoldMinor"), // item find scroll
        GOLD_AVERAGE("gold_average", "ConsumableGoldAverage"),
        GOLD_MAJOR("gold_major", "ConsumableGoldMajor"),

        SPEED_MINOR("speed_minor", "ConsumableSpeedMinor"), // speed kicks
        SPEED_AVERAGE("speed_average", "ConsumableSpeedAverage"),
        SPEED_MAJOR("speed_major", "ConsumableSpeedMajor");

        private String name;
        private String inventoryCue;

        ConsumableType(String name, String inventoryCue) {
            this.name = name;
            this.inventoryCue = inventoryCue;
        }

        public static ConsumableType getTypeFromName(String name) {
            for (ConsumableType type : ConsumableType.values())
                if (type.name.equals(name))
                    return type;
            return null;
        }

        /**
         * Returns name as it appears in e.g. settings.ini.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns image cue from inventory window
         */
        public Cue getInventoryCue() {
            return BrowserManager.cues.get(inventoryCue);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum ItemGrade {
        COMMON("Common", 1),
        RARE("Rare", 2),
        EPIC("Epic", 3),
        LEGENDARY("Legendary", 4);
		/*SET("Set", 5),
		MYTHIC("Mythic", 6),
		ANCIENT("Ancient", 6);*/

        private final String name;
        private final int value;

        ItemGrade(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public static ItemGrade getGradeFromValue(int value) {
            for (ItemGrade grade : ItemGrade.values())
                if (grade.value == value)
                    return grade;
            return null;
        }

        public int getValue() {
            return value;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private enum MinorRuneEffect {
        CAPTURE("Capture"),
        EXPERIENCE("Experience"),
        GOLD("Gold"),
        ITEM_FIND("Item_Find");

        private final String name;

        MinorRuneEffect(String name) {
            this.name = name;
        }

        public static MinorRuneEffect getEffectFromName(String name) {
            for (MinorRuneEffect effect : MinorRuneEffect.values())
                if (effect.name.toLowerCase().equals(name.toLowerCase()))
                    return effect;
            return null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @SuppressWarnings("unused")
    enum MinorRune {
        EXP_COMMON(MinorRuneEffect.EXPERIENCE, ItemGrade.COMMON),
        EXP_RARE(MinorRuneEffect.EXPERIENCE, ItemGrade.RARE),
        EXP_EPIC(MinorRuneEffect.EXPERIENCE, ItemGrade.EPIC),
        EXP_LEGENDARY(MinorRuneEffect.EXPERIENCE, ItemGrade.LEGENDARY),

        ITEM_COMMON(MinorRuneEffect.ITEM_FIND, ItemGrade.COMMON),
        ITEM_RARE(MinorRuneEffect.ITEM_FIND, ItemGrade.RARE),
        ITEM_EPIC(MinorRuneEffect.ITEM_FIND, ItemGrade.EPIC),
        ITEM_LEGENDARY(MinorRuneEffect.ITEM_FIND, ItemGrade.LEGENDARY),

        GOLD_COMMON(MinorRuneEffect.GOLD, ItemGrade.COMMON),
        //		GOLD_RARE(MinorRuneEffect.GOLD, ItemGrade.RARE),
//		GOLD_EPIC(MinorRuneEffect.GOLD, ItemGrade.EPIC),
        GOLD_LEGENDARY(MinorRuneEffect.GOLD, ItemGrade.LEGENDARY),

        CAPTURE_COMMON(MinorRuneEffect.CAPTURE, ItemGrade.COMMON),
        CAPTURE_RARE(MinorRuneEffect.CAPTURE, ItemGrade.RARE),
        CAPTURE_EPIC(MinorRuneEffect.CAPTURE, ItemGrade.EPIC),
        CAPTURE_LEGENDARY(MinorRuneEffect.CAPTURE, ItemGrade.LEGENDARY);

        public static ItemGrade maxGrade = ItemGrade.LEGENDARY;
        private MinorRuneEffect effect;
        private ItemGrade grade;

        MinorRune(MinorRuneEffect effect, ItemGrade grade) {
            this.effect = effect;
            this.grade = grade;
        }

        public static MinorRune getRune(MinorRuneEffect effect, ItemGrade grade) {
            for (MinorRune rune : MinorRune.values()) {
                if (rune.effect == effect && rune.grade == grade)
                    return rune;
            }
            return null;
        }

        public MinorRuneEffect getRuneEffect() {
            return effect;
        }

        public String getRuneCueName() {
            return "MinorRune" + effect + grade;
        }

        public String getRuneCueFileName() {
            return "cues/runes/minor" + effect + grade + ".png";
        }

        public Cue getRuneCue() {
            return BrowserManager.cues.get(getRuneCueName());
        }


        public String getRuneSelectCueName() {
            return "MinorRune" + effect + grade + "Select";
        }

        public String getRuneSelectCueFileName() {
            return "cues/runes/minor" + effect + grade + "Select.png";
        }

        public Cue getRuneSelectCue() {
            return BrowserManager.cues.get(getRuneSelectCueName());
        }

        @Override
        public String toString() {
            return grade.toString().toLowerCase() + "_" + effect.toString().toLowerCase();
        }
    }

    @SuppressWarnings("unused")
    private enum WorldBoss {

        Orlag("o", "Orlag Clan", 1),
        Netherworld("n", "Netherworld", 2),
        Melvin("m", "Melvin", 3),
        Ext3rmin4tion("3", "3xt3rmin4tion", 4),
        BrimstoneSyndicate("b", "Brimstone Syndicate", 5),
        WaterTitans("w", "Water Titans", 6),
        Unknown("?", "Unknown", 7);

        private String letter;
        private String Name;
        private int number;

        WorldBoss(String letter, String Name, int number) {
            this.letter = letter;
            this.Name = Name;
            this.number = number;
        }

        String getLetter() {
            return letter;
        }

        String getName() {
            return Name;
        }

        int getNumber() {
            return number;
        }

        static WorldBoss fromLetter(String Letter) {
            for (WorldBoss wb : WorldBoss.values()) {
                if (wb.getLetter().equals(Letter)) return wb;
            }
            return null;
        }

        static WorldBoss fromNumber(int number) {
            for (WorldBoss wb : WorldBoss.values()) {
                if (wb.getNumber() == number) return wb;
            }
            return null;
        }
    }

    void cueDifference() { //return similarity % between two screenshots taken 3 seconds apart
        BHBot.browser.readScreen();
        BufferedImage img1 = BHBot.browser.getImg();
        Misc.sleep(2500);
        BHBot.browser.readScreen();
        BufferedImage img2 = BHBot.browser.getImg();
        CueCompare.imageDifference(img1, img2, 0.8, 0, 800, 0, 520);
    }

    /*
     * Returns true if it finds a defined Cue
     * You can input with getSegBounds to only search the found item area
     */

    private boolean restrictedCues(Bounds foundArea) {
        MarvinSegment seg;
        HashMap<String, Cue> restrictedCues = new HashMap<>();
        restrictedCues.put("Fire Blossom", BrowserManager.cues.get("Material_R8"));
        restrictedCues.put("Crubble", BrowserManager.cues.get("Material_R7"));
        restrictedCues.put("Beanstalk", BrowserManager.cues.get("Material_R6"));
        restrictedCues.put("Luminous Stone", BrowserManager.cues.get("Material_R5"));
        restrictedCues.put("Rombit", BrowserManager.cues.get("Material_R4"));
        restrictedCues.put("Dubloon", BrowserManager.cues.get("Material_R3"));
        restrictedCues.put("Hyper Shard", BrowserManager.cues.get("Material_R2"));

        for (Map.Entry<String, Cue> cues : restrictedCues.entrySet()) {
            seg = MarvinSegment.fromCue(cues.getValue(), 0, foundArea, BHBot.browser);
            if (seg != null) {
                BHBot.logger.debug("Legendary: " + cues.getKey() + " found, skipping handleLoot");
                return true;
            }
        }
        return false;
    }

    /*
     * Returns true if it finds a defined Cue
     * You can input with getSegBounds to only search the found item area
     */

    private boolean allowedCues(Bounds foundArea) {
        MarvinSegment seg;

        //so we aren't triggered by Skeleton Key heroic cue
        MarvinSegment treasure = MarvinSegment.fromCue(BrowserManager.cues.get("SkeletonTreasure"), BHBot.browser);
        if (treasure != null) {
            return false;
        }

        HashMap<String, Cue> allowedCues = new HashMap<>();
        allowedCues.put("Gold Coin", BrowserManager.cues.get("GoldCoin"));
        allowedCues.put("Heroic Schematic", BrowserManager.cues.get("HeroicSchematic"));
        allowedCues.put("Microprocessing Chip", BrowserManager.cues.get("MicroChip"));
        allowedCues.put("Demon Blood", BrowserManager.cues.get("DemonBlood"));
        allowedCues.put("Hobbit's Foot", BrowserManager.cues.get("HobbitsFoot"));
        allowedCues.put("Melvin Chest", BrowserManager.cues.get("MelvinChest"));
        allowedCues.put("Neural Net Rom", BrowserManager.cues.get("NeuralNetRom"));
        allowedCues.put("Scarlarg Skin", BrowserManager.cues.get("ScarlargSkin"));

        for (Map.Entry<String, Cue> cues : allowedCues.entrySet()) {
            seg = MarvinSegment.fromCue(cues.getValue(), 0, foundArea, BHBot.browser);
            if (seg != null) {
                BHBot.logger.debug(cues.getKey() + " found!");
                return true;
            }
        }
        return false;
    }

    private void handleWeeklyRewards() {
        // check for weekly rewards popup
        // (note that several, 2 or even 3 such popups may open one after another)
        MarvinSegment seg;
        if (state == State.Loading || state == State.Main) {
            BHBot.browser.readScreen();

            HashMap<String, Cue> weeklyRewards = new HashMap<>();
            weeklyRewards.put("PVP", BrowserManager.cues.get("PVP_Rewards"));
            weeklyRewards.put("Trials", BrowserManager.cues.get("Trials_Rewards"));
            weeklyRewards.put("Trials-XL", BrowserManager.cues.get("Trials_Rewards_Large"));
            weeklyRewards.put("Gauntlet", BrowserManager.cues.get("Gauntlet_Rewards"));
            weeklyRewards.put("Gauntlet-XL", BrowserManager.cues.get("Gauntlet_Rewards_Large"));
            weeklyRewards.put("Fishing", BrowserManager.cues.get("Fishing_Rewards"));
            weeklyRewards.put("Invasion", BrowserManager.cues.get("Invasion_Rewards"));
            weeklyRewards.put("Expedition", BrowserManager.cues.get("Expedition_Rewards"));
            weeklyRewards.put("GVG", BrowserManager.cues.get("GVG_Rewards"));

            for (Map.Entry<String, Cue> weeklyRewardEntry : weeklyRewards.entrySet()) {
                seg = MarvinSegment.fromCue(weeklyRewardEntry.getValue(), BHBot.browser);
                if (seg != null) {
                    BufferedImage reward = BHBot.browser.getImg();
                    seg = MarvinSegment.fromCue("X", 5 * SECOND, BHBot.browser);
                    if (seg != null) BHBot.browser.clickOnSeg(seg);
                    else {
                        BHBot.logger.error(weeklyRewardEntry.getKey() + " reward popup detected, however could not detect the X button. Restarting...");
                        restart();
                    }

                    BHBot.logger.info(weeklyRewardEntry.getKey() + " reward claimed successfully.");
                    if ((BHBot.settings.screenshots.contains("w"))) {
                        saveGameScreen(weeklyRewardEntry.getKey().toLowerCase() + "_reward", "rewards", reward);
                    }
                }
            }
        }
    }

}
