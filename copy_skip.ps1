$sourcePath = "d:\corpus_74m\temp\udpipe_2.conllu"
$destPath = "d:\corpus_74m\temp\udpipe_2_no_hdr.conllu"
$skipBytes = 806

$src = [System.IO.File]::OpenRead($sourcePath)
$dst = [System.IO.File]::Create($destPath)

try {
    # Skip the first 806 bytes
    $src.Seek($skipBytes, [System.IO.SeekOrigin]::Begin)
    
    # Use a 4MB buffer for the copy operation
    $src.CopyTo($dst, 4MB)
}
finally {
    $src.Close()
    $dst.Close()
}