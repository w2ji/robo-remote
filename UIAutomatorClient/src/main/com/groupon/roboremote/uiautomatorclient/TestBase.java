/*
 * Copyright (c) 2012, Groupon, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * Neither the name of GROUPON nor the names of its contributors may be
 * used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.</div>
 */

package com.groupon.roboremote.uiautomatorclient;

import com.groupon.roboremote.roboremoteclientcommon.DebugBridge;
import com.groupon.roboremote.roboremoteclientcommon.Device;
import com.groupon.roboremote.roboremoteclientcommon.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.groupon.roboremote.roboremoteclientcommon.logging.*;

import java.io.File;
import java.lang.Exception;
import java.lang.String;
import java.lang.Thread;

public class TestBase {
    public static final Logger logger = LoggerFactory.getLogger("test");
    static String automator_jar = null;
    static AppThread ap = null;

    public static void onFailure() throws Exception {
        logger.warn("com.groupon.roboremote.uiautomatorclient.TestBase::OnFailure:: Taking screenshot");
        DebugBridge.get().getScreenShot("FAILURE.png");
        Device.storeFailurePng();
    }

    public static void setUp(String testName) throws Exception {
        setUp(testName, false, true);
    }

    /**
     * This is the generic test setup function
     * @param relaunch - true if this is an app relaunch
     * @param clearAppData - true if you want app data cleared, false otherwise
     */
    public static void setUp(String testName, Boolean relaunch, Boolean clearAppData) throws Exception{
        if (! relaunch) {
            logger.info("Starting test {}", testName);
            Utils.setTestName(testName);
            Device.setupLogDirectories();
        }

        // see if a server is already listening
        boolean clientWasListening = false;
        if (Client.getInstance().isListening()) {
            clientWasListening = true;
        }

        // wait for the client to stop listening if it was previously listening
        if (clientWasListening) {
            // wait for the server to be dead
            for (int x = 0; x < 10; x++) {
                // try to make a query.. if it doesnt work then sleep
                TestLogger.get().info("Trying to see if server is still available..");

                if (! Client.getInstance().isListening())
                    break;

                if (x == 9)
                    throw new Exception("Server is still available, but should not be");

                Thread.sleep(2000);
            }
        }

        if (! relaunch) {
            // start log listener
            TestLogger.get().info("Clearing logcat");
            DebugBridge.get().clearLogCat();

            TestLogger.get().info("Starting logcat");
            DebugBridge.get().startLogListener(System.getProperty("java.io.tmpdir") +
                    File.separator + "adb_uiauto.log");

            // set up logger
            EmSingleton.intialize();

            EmSingleton.get().clearEvents();
        }

        // starting test runner
        TestLogger.get().info("Starting RC Runner");

        // start app
        startApp();
    }

    // This is called in the failure method override above
    public void tearDown() throws Exception {
        try
        {
            EmSingleton.get().close();
            killApp();

            // stop logcat
            TestLogger.get().info("Stopping logcat");
            DebugBridge.get().stopLogListener();

            // store logs
            Device.storeLogs("adb_uiauto.log", "uiauto.log");
        } catch (Exception e) {

        } finally {
            DebugBridge.get().close();
        }
    }

    public static void setAppEnvironmentVariables(String automator_jar) {
        automator_jar = automator_jar;
    }

    public static void setAppEnvironmentVariables() throws Exception {
        // get environment variables
        automator_jar = Utils.getEnv("ROBO_UIAUTOMATOR_JAR", automator_jar);
        if (automator_jar == null) {
            throw new Exception("ROBO_UIAUTOMATOR_JAR is not set");
        }
    }

    /**
     * Deploys the test jar to the device
     * @return
     * @throws Exception
     */
    public static void deployTestJar() throws Exception {
        File jarFile = new File(automator_jar);
        if (!jarFile.exists())
            throw new Exception("Test jar does not exist: " + automator_jar);

        DebugBridge.get().push(automator_jar, "/data/local/tmp/uiauto.jar");
    }

    public static void startApp() throws Exception {
        ap = new AppThread();
        ap.start();

        for (int x = 0; x < 10; x++) {
            // try to make a query.. if it doesnt work then sleep
            TestLogger.get().info("Trying to ping test server..");
            if (Client.getInstance().isListening())
                break;

            if (x == 9)
                throw new Exception("Could not contact test server");

            Thread.sleep(5000);
        }
    }

    public static void killApp() throws Exception {
        // try to kill just by calling exit
        try {
            Client.getInstance().map("java.lang.System", "exit", 0);
        } catch (Exception e) {
            // this will actually throw an exception since it doesnt get a response from this command
        }

        // shut down the thread
        if (ap != null) {
            ap.close();
            ap.interrupt();
            ap = null;
        }

        // wait for the server to be dead
        for (int x = 0; x < 10; x++) {
            // try to make a query.. if it doesnt work then sleep
            TestLogger.get().info("Trying to see if server is still available..");

            if (! Client.getInstance().isListening())
                break;

            if (x == 9)
                throw new Exception("Server is still available, but should not be");

            Thread.sleep(2000);
        }
    }

    /**
     * This thread contains the running RC test
     * DebugBridge does not return until the instrumentation finishes so we have to run it in its own thread
     */
    private static class AppThread extends Thread {
        DebugBridge.MultiReceiver _receiver = null;

        public void run() {
            _receiver = new DebugBridge.MultiReceiver();
            try {
                // create adb tunnel
                DebugBridge.get().createTunnel(Constants.UIAUTOMATOR_PORT, Constants.UIAUTOMATOR_PORT);

                // run uiautomator
                DebugBridge.get().runShellCommand("uiautomator runtest uiauto.jar -c com.groupon.roboremote.uiautomatorserver.RemoteTest", _receiver, 0);
            } catch (Exception e) {

            }
        }

        public void close() {
            // close the receiver to kill the thread
            _receiver.close();
        }
    }
}