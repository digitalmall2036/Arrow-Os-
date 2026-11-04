// The small, safe bridge between the Arrow Os window and the main process.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('arrow', {
  pageHelpers: () => ipcRenderer.invoke('page-helpers'),
  startVoice: () => ipcRenderer.invoke('voice-start'),
  stopVoice: () => ipcRenderer.invoke('voice-stop'),
  toggleFullscreen: () => ipcRenderer.invoke('toggle-fullscreen'),
  showDownload: (p) => ipcRenderer.invoke('show-download', p),
  openDownload: (p) => ipcRenderer.invoke('open-download', p),
  onVoice: (fn) => ipcRenderer.on('voice', (_e, msg) => fn(msg)),
  onDownload: (fn) => ipcRenderer.on('download', (_e, msg) => fn(msg)),
  onShortcut: (fn) => ipcRenderer.on('shortcut', (_e, name) => fn(name)),
  onFullscreen:(fn) => ipcRenderer.on('fullscreen', (_e, on) => fn(on)),
});
