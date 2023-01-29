/*
  StorageManager.java

  Firefly Luciferin, very fast Java Screen Capture software designed
  for Glow Worm Luciferin firmware.

  Copyright © 2020 - 2023  Davide Perini  (https://github.com/sblantipodi)

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package org.dpsoftware.managers;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.dpsoftware.FireflyLuciferin;
import org.dpsoftware.JavaFXStarter;
import org.dpsoftware.LEDCoordinate;
import org.dpsoftware.NativeExecutor;
import org.dpsoftware.config.Constants;
import org.dpsoftware.config.LocalizedEnum;
import org.dpsoftware.grabber.ImageProcessor;
import org.dpsoftware.gui.elements.DisplayInfo;
import org.dpsoftware.utilities.CommonUtility;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * Write and read yaml configuration file
 */
@Slf4j
@Getter
@Setter
public class PowerSavingManager {

    public boolean shutDownLedStrip = false;
    public boolean unlockCheckLedDuplication = false;
    public LocalDateTime lastFrameTime;
    public Color[] ledArray;
    PowerSavingScreenSaver powerSavingScreenSaver = PowerSavingScreenSaver.NOT_TRIGGERED;
    boolean screenSaverTaskNeeded = false;
    boolean screenSaverRunning = false;

    /**
     * Execute a task that checks if screensaver is enabled/running.
     */
    public void addPowerSavingTask() {
        log.debug("Adding hook for power saving.");
        ScheduledExecutorService scheduledExecutorServiceSS = Executors.newScheduledThreadPool(1);
        scheduledExecutorServiceSS.scheduleAtFixedRate(() -> {
            if (!FireflyLuciferin.RUNNING) {
                takeScreenshot(false);
            }
            managePowerSavingLeds();
            unlockCheckLedDuplication = true;
            // The methods below must run in a separate thread from the capture pipeline
            screenSaverTaskNeeded = isScreenSaverTaskNeeded();
            if (NativeExecutor.isWindows()) {
                screenSaverRunning = NativeExecutor.isScreensaverRunning();
            }
        }, 60, 15, TimeUnit.SECONDS);
    }

    /**
     * Turn OFF/ON LEds if a power saving event has been triggered
     */
    private void managePowerSavingLeds() {
        if ((screenSaverTaskNeeded && screenSaverRunning) || shutDownLedStrip) {
            if (powerSavingScreenSaver != PowerSavingScreenSaver.TRIGGERED_NOT_RUNNING &&
                    powerSavingScreenSaver != PowerSavingScreenSaver.TRIGGERED_RUNNING) {
                if (FireflyLuciferin.RUNNING) {
                    powerSavingScreenSaver = PowerSavingScreenSaver.TRIGGERED_RUNNING;
                    shutDownLedStrip = true;
                } else {
                    powerSavingScreenSaver = PowerSavingScreenSaver.TRIGGERED_NOT_RUNNING;
                    CommonUtility.turnOffLEDs(FireflyLuciferin.config);
                    takeScreenshot(true);
                }
                log.debug("Power saving on.");
            }
        } else {
            if (powerSavingScreenSaver != PowerSavingScreenSaver.NOT_TRIGGERED) {
                if (powerSavingScreenSaver == PowerSavingScreenSaver.TRIGGERED_RUNNING) {
                    log.debug("Power saving off.");

                } else if (powerSavingScreenSaver == PowerSavingScreenSaver.TRIGGERED_NOT_RUNNING) {
                    CommonUtility.turnOnLEDs();
                    log.debug("Power saving off.");
                }
                powerSavingScreenSaver = PowerSavingScreenSaver.NOT_TRIGGERED;
            }
        }
    }

    /**
     * Take screenshot of the screen to check if the image on screen is changed.
     *
     * @param overWriteLedArray true when turning off LEDs while screen capturing. during screen capture the imaage is
     *                          captured using GPU accelerated API, when there is no screen capture the image is captured
     *                          via a simple screenshot. this is needed when switching to from screen capture to no screen capture
     *                          to align the arrays used to check differences.
     */
    @SuppressWarnings("unchecked")
    public void takeScreenshot(boolean overWriteLedArray) {
        Robot robot;
        try {
            robot = new Robot();
            // TODO single monitor
//            ImageProcessor.screen = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
            DisplayManager displayManager = new DisplayManager();
            DisplayInfo monitorInfo = displayManager.getDisplayInfo(FireflyLuciferin.config.getMonitorNumber());
            ImageProcessor.screen = robot.createScreenCapture(new Rectangle(
                    (int) (monitorInfo.getDisplayInfoAwt().getMinX() / monitorInfo.getScaleX()),
                    (int) (monitorInfo.getDisplayInfoAwt().getMinY() / monitorInfo.getScaleX()),
                    (int) (monitorInfo.getDisplayInfoAwt().getWidth() / monitorInfo.getScaleX()),
                    (int) (monitorInfo.getDisplayInfoAwt().getHeight() / monitorInfo.getScaleX())
            ));
            try {
                ImageIO.write(ImageProcessor.screen, "png", new java.io.File("screenshot"+ JavaFXStarter.whoAmI+".png"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            int osScaling = FireflyLuciferin.config.getOsScaling();
            Color[] ledsScreenshotTmp = new Color[ImageProcessor.ledMatrix.size()];
            LinkedHashMap<Integer, LEDCoordinate> ledMatrixTmp = (LinkedHashMap<Integer, LEDCoordinate>) ImageProcessor.ledMatrix.clone();
            // We need an ordered collection so no parallelStream here
            ledMatrixTmp.forEach((key, value) -> {
                if (overWriteLedArray) {
                    ledArray[key - 1] = ImageProcessor.getAverageColor(value, osScaling);
                } else {
                    ledsScreenshotTmp[key - 1] = ImageProcessor.getAverageColor(value, osScaling);
                }
            });
            if (!overWriteLedArray) {
                checkForLedDuplication(ledsScreenshotTmp);
            }
        } catch (AWTException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * Check if screen saver detection is needed.
     * Screen saver detection works on Windows only, when Power Saving is enabled and when Screen Saver is enabled.
     */
    public boolean isScreenSaverTaskNeeded() {
        return NativeExecutor.isWindows() && (!Constants.PowerSaving.DISABLED.equals(LocalizedEnum.fromBaseStr(Constants.PowerSaving.class,
                FireflyLuciferin.config.getPowerSaving()))) && NativeExecutor.isScreenSaverEnabled();
    }

    /**
     * If there is LEDs duplication for more than N seconds, turn off the lights for power saving
     *
     * @param leds array containing colors
     */
    public void checkForLedDuplication(Color[] leds) {
        if (!isLedArraysEqual(leds)) {
            lastFrameTime = LocalDateTime.now();
            ledArray = Arrays.copyOf(leds, leds.length);
        }
        int minutesToShutdown = Integer.parseInt(FireflyLuciferin.config.getPowerSaving().split(" ")[0]);
        if (!screenSaverTaskNeeded || !screenSaverRunning) {
            // TODO rimetti
//            shutDownLedStrip = lastFrameTime.isBefore(LocalDateTime.now().minusMinutes(minutesToShutdown));
            shutDownLedStrip = lastFrameTime.isBefore(LocalDateTime.now().minusSeconds(30));
        }
    }

    /**
     * Check if the current led array is equal to the previous saved one
     *
     * @param leds array
     * @return if two frames are identical, return true.
     */
    public boolean isLedArraysEqual(Color[] leds) {
        final int LED_TOLERANCE = 2;
        int difference = 0;
        if (ledArray == null) {
            return false;
        }
        for (int i = 0; i < leds.length; i++) {
            if (leds[i].getRGB() != ledArray[i].getRGB()) {
                difference++;
                if (difference > LED_TOLERANCE) return false;
            }
        }
        return true;
    }

    enum PowerSavingScreenSaver {
        NOT_TRIGGERED,
        TRIGGERED_RUNNING,
        TRIGGERED_NOT_RUNNING
    }

}