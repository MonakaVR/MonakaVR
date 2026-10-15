using System;
using System.IO;
using System.Threading;
internal static class SleepingCommit {
 static int Main(string[] a){if(a.Length==2&&a[1]=="transaction-version"){Console.WriteLine("monaka-config-transaction-v1");return 0;}
 if(a.Length==4&&a[1]=="commit"){if(Path.GetFileName(a[0]).StartsWith("postcommit"))File.Copy(a[3],a[0],true);File.WriteAllText(a[0]+".sleeping-commit","ready");Thread.Sleep(45000);return 1;}return 1;}
}
