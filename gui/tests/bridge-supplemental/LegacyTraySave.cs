// Test harness only. Compiled with the unchanged frozen Bridge GUI sources.
using System;
using System.IO;
using System.Reflection;
using System.Runtime.Serialization;
using MonakaBridge;

internal static class LegacyTraySave
{
    [STAThread] static int Main(string[] args)
    {
        try
        {
            if (args.Length != 2 || !Path.IsPathRooted(args[0])) throw new ArgumentException("isolated root and policy required");
            var type = typeof(SteamVrControlTray);
            // Avoid constructor/NotifyIcon, status shared memory, and SetRoute's
            // global service notification. Invoke the actual file-save method.
            var tray = FormatterServices.GetUninitializedObject(type);
            foreach (var pair in new[] { new[] { "repoRoot", args[0] }, new[] { "configDirectory", Path.Combine(args[0], "config") } })
                type.GetField(pair[0], BindingFlags.NonPublic | BindingFlags.Instance).SetValue(tray, pair[1]);
            var route = type.GetNestedType("OutputRoute", BindingFlags.NonPublic);
            var name = args[1] == "monaka" ? "Monaka" : args[1] == "both" ? "Both" : args[1] == "disabled" ? "Disabled" : "SteamVr";
            type.GetMethod("SaveRoute", BindingFlags.NonPublic | BindingFlags.Instance).Invoke(tray, new[] { Enum.Parse(route, name) });
            Console.WriteLine("PASS actual frozen legacy tray SaveRoute only; no SetRoute/event/MMF/OS interaction");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }
}
