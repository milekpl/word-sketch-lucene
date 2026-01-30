# Clean stderr garbage from CoNLL-U files
$files = @(
    'D:/corpus_74m/temp/udpipe_0.conllu',
    'D:/corpus_74m/temp/udpipe_1.conllu',
    'D:/corpus_74m/temp/udpipe_2.conllu',
    'D:/corpus_74m/temp/udpipe_3.conllu'
)

foreach ($f in $files) {
    $content = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
    # Remove stderr garbage lines
    $clean = $content -replace 'udpipe\.exe : .+?RemoteException\r?\n', '' -replace 'Loading UDPipe model: done\.\r?\n', ''
    [System.IO.File]::WriteAllText($f, $clean, [System.Text.Encoding]::UTF8)
    Write-Host "Cleaned $f"
}

Write-Host "Done cleaning files"
