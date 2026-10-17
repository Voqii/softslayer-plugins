"""Patch Brightness Aura 1.6.6+26.2 KeyMappingImpl.class for 26.3.

- InputConstants$Type.KEYSYM -> KEYBOARD (renamed in 26.3): rewrite the lone Utf8 entry.
  Constant pool entries are variable-length and nothing points at pool byte offsets,
  so growing the string is safe.
- Default key 66 (GLFW 'B') -> 5 (SDL scancode 'B'): bipush operand, same length.
"""
import struct, sys

src, dst = sys.argv[1], sys.argv[2]
d = bytearray(open(src, 'rb').read())
old, new = b'KEYSYM', b'KEYBOARD'

needle = b'\x01' + struct.pack('>H', len(old)) + old
assert d.count(needle) == 1, 'expected exactly one KEYSYM Utf8'
d = d.replace(needle, b'\x01' + struct.pack('>H', len(new)) + new)

# getstatic #87 ; bipush 66
code = b'\xb2\x00\x57\x10\x42'
assert d.count(code) == 1, 'expected exactly one getstatic KEYSYM; bipush 66'
d = d.replace(code, b'\xb2\x00\x57\x10\x05')

open(dst, 'wb').write(d)
print('patched', dst)
