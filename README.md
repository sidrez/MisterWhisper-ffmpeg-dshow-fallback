# MisterWhisper
 

MisterWhisper is an open-source application designed to simplify your workflow by transforming spoken words into text in real-time. When you press a designated key (F1 to F18), the application records your voice, transcribes it using the powerful Whisper AI model (GPU-accelerated for fast and efficient recognition), and directly inputs the resulting text into the currently active software.

MisterWhisper supports over 100 languages, making it a robust multilingual transcription tool.

![MisterWhisper](https://raw.githubusercontent.com/openconcerto/MisterWhisper/refs/heads/main/tray.png)


# Features

- Quick voice transcription: Record and transcribe speech only while the designated key is pressed, like a walkie-talkie.

- Integration with active software: Automatically inputs transcribed text into the application you are currently using.

- GPU acceleration (optional): Powered by [whisper.cpp](https://github.com/ggerganov/whisper.cpp) for fast and accurate voice recognition.

- Local or remote : You can use the included Whisper transcription locally or connect to a remote service for transcription.

- Optional Mistral text post-processing: Enable **Post-processing** in the tray menu to correct spelling, punctuation and obvious typos before text is pasted, typed and stored in history.

# Usage

There are two ways to start recording. 
- press the "F9" (you can change this hotkey) key quickly once to start, and then press F9 again to stop.
- hold down the F9 key. As soon as you release it, the recording will stop.

Note that the software detects silences and will start the transcription as soon as it detects one.

# Installation

- extract the provided zip (or jar) file or compile your own version of MisterWhisper
- optionaly, download a Whisper model (.bin file) from : https://huggingface.co/ggerganov/whisper.cpp/tree/main and copy it in the *models* folder 
(the best model is ggml-large-v3-turbo.bin)

# Windows (7,8,10,11..) versions

Download and extract the zip file from the Releases corresponding to your configuration :
- cpu version : if you have no GPU
- cuda version : for nVidia GPU
- vulkan version : should work with any modern GPU (be patient on first launch, shaders compilation takes time)

Just launch the *MisterWhisper.exe*.

Keep F9 pressed while talking, the text will be inserted into the currently active software after key release.

To access the settings or view the history, simply right-click on the icon in the taskbar.

## Optional text post-processing with Mistral

MisterWhisper can run recognized text through Mistral after Whisper transcription and before paste/type/history.

1. Set the API key in the environment variable `MISTRAL_API_KEY`.
2. Start MisterWhisper.
3. Right-click the tray icon and enable `Post-processing`.

The setting is saved in Preferences and restored on startup. If the API key is missing, the network request fails, the API returns an error, or the response cannot be parsed, MisterWhisper keeps using the original Whisper text.

You can update the whispercpp provided in MistterWhisper by replacing dlls and exe with the latest prebuilt binaries ( https://github.com/ggml-org/whisper.cpp/releases/latest ).
It can resolve driver compatibilty issue (RTX 50x0 or newer cards).

# Linux and macOS versions

MisterWhisper requires a Java runtime to be installed (version 8 or newer) to use *MisterWhisper.jar*.

Providing a precompiled WhisperCpp library is not straightforward. 

You'll need to compile whisper.cpp and use the client-server mode :

`` 
whisper-server --no-timestamps -l auto --port 9595 -t 8 -m "models/ggml-large-v3-turbo-q8_0.bin"
``

And

`` 
java -jar MisterWhisper.jar "http://127.0.0.1:9595/inference"
``

# Advanced Usage (client-server mode)
If you want to use a remote server, launch the *whisper.cpp* server on the remote machine, for example (the server ip is 192.168.1.100) :

`` 
server.exe --no-timestamps -l auto --port 9595 -t 8 -m "models/ggml-large-v3-turbo-q8_0.bin"
``

On the local machine, add the remote url as first parameter : 

`` 
MisterWhisper.exe "http://192.168.1.100:9595/inference"
``

# Windows local dev workflow (from repo only, no `.exe`)

Use this when you run directly from Java classes (for example with a PowerShell `mw` function), not from a packaged executable.

## 1) Where to get Whisper/CUDA DLLs

Put native DLLs into `runtime\win32-x86-64`.

Recommended source:
- Extract the **Windows CUDA** release archive (`MisterWhisper-*-windows-cuda.zip`) and use that folder as source for:
  - `whisper.dll`
  - `ggml.dll`
  - `ggml-base.dll`
  - `ggml-cpu.dll`
  - `ggml-cuda.dll`
  - `cudart64_110.dll`
  - `cublas64_11.dll`
  - `cublasLt64_11.dll`

Bootstrap from that extracted release folder:
```
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-runtime-from-release.ps1 -ReleasePath "C:\path\to\MisterWhisper-1.3-windows-cuda"
```

Notes:
- If `-ReleasePath` is omitted, script uses its default temp path.
- You can also replace these DLLs with newer compatible `whisper.cpp` prebuilt Windows binaries if needed.

## 2) Build from source and stage runtime DLLs

```
powershell -ExecutionPolicy Bypass -File .\scripts\build-and-run.ps1
```

This compiles Java sources into `out\` and copies runtime DLLs into `out\win32-x86-64`.

## 3) Run

Direct Java command:
```
java -cp "out;lib\jna.jar;lib\jnativehook-2.2.2.jar;lib\win32-x86-64.jar" whisper.MisterWhisper --window --debug
```

Or via PowerShell helper function `mw`:
```powershell
function mw {
    Set-Location "c:\Users\ar.sitdikov\OneDrive - ООО УК Унистрой\Work\Soft\Автоматизация\Python\Разное\Apps\MisterWhisper-ffmpeg-dshow-fallback"
    java -cp "out;lib\jna.jar;lib\jnativehook-2.2.2.jar;lib\win32-x86-64.jar" whisper.MisterWhisper --window --debug
}
```

Then run:
```powershell
mw
```

## Optional: save `mw` in PowerShell profile

To make `mw` available in every new PowerShell session:

```powershell
if (!(Test-Path -LiteralPath $PROFILE)) { New-Item -ItemType File -Path $PROFILE -Force | Out-Null }
notepad $PROFILE
```

Add:

```powershell
function mw {
    Set-Location "c:\Users\ar.sitdikov\OneDrive - ООО УК Унистрой\Work\Soft\Автоматизация\Python\Разное\Apps\MisterWhisper-ffmpeg-dshow-fallback"
    java -cp "out;lib\jna.jar;lib\jnativehook-2.2.2.jar;lib\win32-x86-64.jar" whisper.MisterWhisper --window --debug
}
```

Restart PowerShell (or run `. $PROFILE`) and use:

```powershell
mw
```

# Acknowledgements

Georgi Gerganov : For its state-of-the-art, efficient [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Demonstrating that we don't need an abundance of low-quality Python software for AI tools.

OpenAI : For the open-source [Whisper](https://github.com/openai/whisper) project.

