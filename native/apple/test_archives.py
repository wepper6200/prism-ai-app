import bz2, ctypes, io, pathlib, sys, tarfile, tempfile, zipfile
lib = ctypes.CDLL(sys.argv[1])
lib.prism_unpack_voice.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
lib.prism_docx_xml.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
lib.prism_docx_xml.restype = ctypes.c_void_p
libc = ctypes.CDLL(None)
libc.free.argtypes = [ctypes.c_void_p]
with tempfile.TemporaryDirectory() as folder:
    root = pathlib.Path(folder)
    def archive(name, kind=tarfile.REGTYPE):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as tar:
            item = tarfile.TarInfo(name); item.type = kind
            payload = b'offline voice fixture'
            if kind == tarfile.REGTYPE: item.size = len(payload)
            if kind == tarfile.SYMTYPE: item.linkname = '../outside'
            tar.addfile(item, io.BytesIO(payload) if kind == tarfile.REGTYPE else None)
        path = root / 'model.tar.bz2'; path.write_bytes(bz2.compress(stream.getvalue())); return str(path).encode()
    dest = str(root / 'voice').encode()
    assert lib.prism_unpack_voice(archive('voice/tts.json'), dest) == 1
    assert (root / 'voice/voice/tts.json').read_bytes() == b'offline voice fixture'
    assert lib.prism_unpack_voice(archive('../escape'), dest) == 0
    assert not (root / 'escape').exists()
    assert lib.prism_unpack_voice(archive('link', tarfile.SYMTYPE), dest) == 0
    assert (root / 'voice/voice/tts.json').exists(), 'Failed extraction must preserve the previous model'
    for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        stream = io.BytesIO(); expected = '<w:document>Привет</w:document>'.encode()
        with zipfile.ZipFile(stream, 'w', compression=compression) as z:
            z.writestr('../ignored', b'no extraction'); z.writestr('word/document.xml', expected)
        data = stream.getvalue(); size = ctypes.c_size_t()
        pointer = lib.prism_docx_xml(data, len(data), ctypes.byref(size))
        assert pointer and ctypes.string_at(pointer, size.value) == expected
        libc.free(pointer)
        assert not lib.prism_docx_xml(data[:20], 20, ctypes.byref(size))
    damaged = bytearray(data)
    start = data.index(b'word/document.xml') + len(b'word/document.xml')
    damaged[start] ^= 64
    assert not lib.prism_docx_xml(bytes(damaged), len(damaged), ctypes.byref(size))
print('Archive, traversal, symlink, DOCX CRC and truncation checks passed')
