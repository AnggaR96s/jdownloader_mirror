/**
 *
 * ====================================================================================================================================================
 *         "AppWork Utilities" License
 *         The "AppWork Utilities" will be called [The Product] from now on.
 * ====================================================================================================================================================
 *         Copyright (c) 2009-2025, AppWork GmbH <e-mail@appwork.org>
 *         Spalter Strasse 58
 *         91183 Abenberg
 *         e-mail@appwork.org
 *         Germany
 * === Preamble ===
 *     This license establishes the terms under which the [The Product] Source Code & Binary files may be used, copied, modified, distributed, and/or redistributed.
 *     The intent is that the AppWork GmbH is able to provide  their utilities library for free to non-commercial projects whereas commercial usage is only permitted after obtaining a commercial license.
 *     These terms apply to all files that have the [The Product] License header (IN the file), a <filename>.license or <filename>.info (like mylib.jar.info) file that contains a reference to this license.
 *
 * === 3rd Party Licences ===
 *     Some parts of the [The Product] use or reference 3rd party libraries and classes. These parts may have different licensing conditions. Please check the *.license and *.info files of included libraries
 *     to ensure that they are compatible to your use-case. Further more, some *.java have their own license. In this case, they have their license terms in the java file header.
 *
 * === Definition: Commercial Usage ===
 *     If anybody or any organization is generating income (directly or indirectly) by using [The Product] or if there's any commercial interest or aspect in what you are doing, we consider this as a commercial usage.
 *     If your use-case is neither strictly private nor strictly educational, it is commercial. If you are unsure whether your use-case is commercial or not, consider it as commercial or contact as.
 * === Dual Licensing ===
 * === Commercial Usage ===
 *     If you want to use [The Product] in a commercial way (see definition above), you have to obtain a paid license from AppWork GmbH.
 *     Contact AppWork for further details: e-mail@appwork.org
 * === Non-Commercial Usage ===
 *     If there is no commercial usage (see definition above), you may use [The Product] under the terms of the
 *     "GNU Affero General Public License" (http://www.gnu.org/licenses/agpl-3.0.en.html).
 *
 *     If the AGPL does not fit your needs, please contact us. We'll find a solution.
 * ====================================================================================================================================================
 * ==================================================================================================================================================== */
package org.appwork.jna.windows;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;

/**
 * @author thomas
 * @date 15.05.2025
 *
 */
@Structure.FieldOrder({ "Process", "strAppName", "strServiceShortName", "ApplicationType", "AppStatus", "TSSessionId", "bRestartable" })
public class RmProcessInfo extends Structure {
    @Structure.FieldOrder({ "dwProcessId", "ProcessStartTime" })
    public static class RmUniqueProcess extends Structure {
        public int              dwProcessId;
        public WinBase.FILETIME ProcessStartTime;
    }

    int                    CCH_RM_SESSION_KEY  = 32;
    int                    CCH_RM_MAX_APP_NAME = 255;
    int                    CCH_RM_MAX_SVC_NAME = 63;
    public RmUniqueProcess Process;
    public char[]          strAppName          = new char[CCH_RM_MAX_APP_NAME + 1];
    public char[]          strServiceShortName = new char[CCH_RM_MAX_SVC_NAME + 1];
    public int             ApplicationType;
    public WinDef.LONG     AppStatus;
    public int             TSSessionId;
    public boolean         bRestartable;

    @Override
    public String toString() {
        String appName = Native.toString(strAppName);
        String serviceName = Native.toString(strServiceShortName);
        return String.format("PID: %d%n" + "AppName: %s%n" + "ServiceName: %s%n" + "AppType: %d%n" + "AppStatus: %d%n" + "SessionId: %d%n" + "Restartable: %b%n", Process, appName, serviceName, ApplicationType, AppStatus, TSSessionId, bRestartable);
    }
}