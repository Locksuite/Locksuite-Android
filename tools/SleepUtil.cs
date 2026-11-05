using System;
using System.Runtime.InteropServices;

namespace Win32Sleep {
    public static class SleepUtil {
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern uint SetThreadExecutionState(uint esFlags);

        public const uint ES_CONTINUOUS = 0x80000000;
        public const uint ES_SYSTEM_REQUIRED = 0x00000001;
        public const uint ES_AWAYMODE_REQUIRED = 0x00000040;

        public static void PreventSleep() {
            SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED);
        }

        public static void AllowSleep() {
            SetThreadExecutionState(ES_CONTINUOUS);
        }
    }
}
