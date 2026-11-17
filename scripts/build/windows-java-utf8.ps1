param([Parameter(Mandatory=$true)][string]$Java)
# Applies only to the generated Harnex launcher copy. Keep the vendor's java.exe intact.
# Windows 10 1903+ lets a process opt into UTF-8 without changing the machine locale.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -ReferencedAssemblies System.dll,System.Xml.dll -TypeDefinition @'
using System;
using System.Text;
using System.Xml;
using System.Runtime.InteropServices;
using System.Collections.Generic;
public static class HarnexJavaManifest {
  [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
  [DllImport("kernel32", SetLastError=true)] static extern bool FreeLibrary(IntPtr module);
  [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr FindResourceEx(IntPtr module, IntPtr type, IntPtr name, ushort language);
  [DllImport("kernel32", SetLastError=true)] static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
  [DllImport("kernel32", SetLastError=true)] static extern IntPtr LockResource(IntPtr resource);
  [DllImport("kernel32", SetLastError=true)] static extern uint SizeofResource(IntPtr module, IntPtr resource);
  delegate bool LangCallback(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr parameter);
  [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool EnumResourceLanguages(IntPtr module, IntPtr type, IntPtr name, LangCallback callback, IntPtr parameter);
  [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr BeginUpdateResource(string path, bool deleteExisting);
  [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool UpdateResource(IntPtr handle, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
  [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool EndUpdateResource(IntPtr handle, bool discard);
  static Exception Error() { return new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error()); }
  public static void Patch(string path) {
    var type = new IntPtr(24); var id = new IntPtr(1);
    var module = LoadLibraryEx(path, IntPtr.Zero, 2);
    if (module == IntPtr.Zero) throw Error();
    var resources = new Dictionary<ushort, byte[]>();
    try {
      var languages = new List<ushort>();
      LangCallback callback = (m,t,n,l,p) => { languages.Add(l); return true; };
      if (!EnumResourceLanguages(module,type,id,callback,IntPtr.Zero)) throw Error();
      foreach (var language in languages) {
        var info = FindResourceEx(module,type,id,language);
        if(info == IntPtr.Zero) throw Error();
        var data = LockResource(LoadResource(module,info));
        if(data == IntPtr.Zero) throw Error();
        var bytes = new byte[SizeofResource(module,info)]; Marshal.Copy(data,bytes,0,bytes.Length);
        var xml = new XmlDocument(); xml.LoadXml(Encoding.UTF8.GetString(bytes).TrimEnd('\0'));
        var ns = new XmlNamespaceManager(xml.NameTable); ns.AddNamespace("a","urn:schemas-microsoft-com:asm.v3");
        var application = xml.DocumentElement.SelectSingleNode("a:application",ns);
        if (application == null) { application = xml.CreateElement("application","urn:schemas-microsoft-com:asm.v3"); xml.DocumentElement.AppendChild(application); }
        var settings = application.SelectSingleNode("a:windowsSettings",ns);
        if(settings == null) { settings = xml.CreateElement("windowsSettings","urn:schemas-microsoft-com:asm.v3"); application.AppendChild(settings); }
        var codepage = settings.SelectSingleNode("*[local-name()='activeCodePage' and namespace-uri()='http://schemas.microsoft.com/SMI/2019/WindowsSettings']");
        if(codepage == null) { codepage = xml.CreateElement("activeCodePage","http://schemas.microsoft.com/SMI/2019/WindowsSettings"); settings.AppendChild(codepage); }
        codepage.InnerText="UTF-8";
        resources[language] = Encoding.UTF8.GetBytes(xml.OuterXml);
      }
    } finally { FreeLibrary(module); }
    var handle = BeginUpdateResource(path,false); if(handle == IntPtr.Zero) throw Error();
    try { foreach(var pair in resources) if(!UpdateResource(handle,type,id,pair.Key,pair.Value,(uint)pair.Value.Length)) throw Error(); }
    catch { EndUpdateResource(handle,true); throw; }
    if(!EndUpdateResource(handle,false)) throw Error();
  }
}
'@
[HarnexJavaManifest]::Patch($Java)
