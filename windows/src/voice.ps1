# Arrow Os voice listener for Windows.
#
# Uses Windows' own offline speech recognizer (System.Speech), so it needs no
# internet and no API key. Prints one line per event on stdout:
#   READY:<language>   listening has started
#   PHRASE:<text>      something was said
#   ERROR:<code>       no-recognizer | no-mic | other message
# The Electron app starts this script when the mic is on and kills it when it's off.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Say([string]$line) {
  [Console]::Out.WriteLine($line)
  [Console]::Out.Flush()
}

try {
  Add-Type -AssemblyName System.Speech
} catch {
  Say 'ERROR:no-recognizer'
  exit 2
}

$recognizers = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
if ($recognizers.Count -eq 0) {
  Say 'ERROR:no-recognizer'
  exit 2
}

# Arrow Os commands are English words, so prefer an English recognizer.
$info = $recognizers | Where-Object { $_.Culture.TwoLetterISOLanguageName -eq 'en' } | Select-Object -First 1
if (-not $info) { $info = $recognizers[0] }

$engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine($info)

# A grammar that expects "tap ..." or "search ..." so the command words are heard
# reliably, plus free dictation for anything else.
$words = New-Object System.Speech.Recognition.Choices
$words.Add([string[]]@('tap', 'search', 'hey arrow tap', 'hey arrow search'))
$builder = New-Object System.Speech.Recognition.GrammarBuilder
$builder.Culture = $info.Culture
$builder.Append($words)
$builder.AppendDictation()
$commandGrammar = New-Object System.Speech.Recognition.Grammar($builder)
$commandGrammar.Name = 'command'
$commandGrammar.Priority = 10
$engine.LoadGrammar($commandGrammar)

$dictation = New-Object System.Speech.Recognition.DictationGrammar
$dictation.Name = 'dictation'
$engine.LoadGrammar($dictation)

try {
  $engine.SetInputToDefaultAudioDevice()
} catch {
  Say 'ERROR:no-mic'
  exit 3
}

$engine.EndSilenceTimeout = [TimeSpan]::FromMilliseconds(700)
Say ('READY:' + $info.Culture.Name)

while ($true) {
  try {
    $result = $engine.Recognize([TimeSpan]::FromSeconds(15))
  } catch {
    Say ('ERROR:' + $_.Exception.Message)
    exit 4
  }
  if ($result -and $result.Text -and $result.Confidence -ge 0.2) {
    Say ('PHRASE:' + $result.Text)
  }
}
