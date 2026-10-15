using System;
using System.IO;
using System.Threading;
internal static class SleepingCommit {
 static int Main(string[] a){if(a.Length==2&&a[1]=="transaction-version"){Console.WriteLine("monaka-config-transaction-v1");return 0;}
 if(a.Length==4&&a[1]=="commit"){File.WriteAllText(a[0]+".sleeping-commit","ready");Thread.Sleep(45000);return 1;}return 1;}
}
