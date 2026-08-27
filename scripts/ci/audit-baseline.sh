#!/bin/bash
#
# audit-baseline.sh — fail CI if a native-image binary uses CPU instructions
# newer than the baseline x86-64 / armv8-a march that GraalVM "compatibility"
# targets. Catches AVX/BMI/FMA (x86) regressions that break pre-AVX2 machines.
#
# Usage: audit-baseline.sh <path-to-native-binary>
#
# Exits nonzero if any advanced opcode mnemonic is found in the disassembly.

set -euo pipefail

BINARY="${1:?usage: audit-baseline.sh <path-to-native-binary>}"

if [ ! -f "$BINARY" ]; then
    echo "ERROR: binary not found: $BINARY" >&2
    exit 2
fi

# Locate a disassembler: objdump (ELF/linux/PE), llvm-objdump, or dumpbin (MSVC).
DIS=""
for cand in objdump llvm-objdump; do
    if command -v "$cand" >/dev/null 2>&1; then
        DIS="$cand"
        break
    fi
done
DUMPBIN=""
if command -v dumpbin >/dev/null 2>&1; then
    DUMPBIN="dumpbin"
fi
if [ -n "$DIS" ]; then
    echo "audit-baseline: using $DIS"
elif [ -n "$DUMPBIN" ]; then
    DIS="dumpbin"
    echo "audit-baseline: using $DUMPBIN (MSVC)"
else
    echo "ERROR: no disassembler found (need objdump, llvm-objdump, or dumpbin)" >&2
    exit 2
fi

# Detect architecture.
MARCH=""
if [ "$DIS" = "objdump" ] || [ "$DIS" = "llvm-objdump" ]; then
    MARCH=$("$DIS" -f "$BINARY" 2>/dev/null \
            | grep -oE 'architecture: (i386|x86-64|aarch64|arm[^,]*)' \
            | sed 's/architecture: //' | head -1 || true)
    case "$MARCH" in
      i386*|x86-64) MARCH=x86-64 ;;
      aarch64|arm64|arm*) MARCH=aarch64 ;;
    esac
fi
if [ -z "$MARCH" ]; then
    # Fall back to the file's first bytes (ELF e_machine / PE machine field).
    case "$BINARY" in
      *.exe|*.dll) MARCH=x86-64 ;; # win32 job builds x86-64 PE
      *) MARCH=x86-64 ;;
    esac
    echo "audit-baseline: arch detected as '$MARCH' (fallback)"
fi

# Advanced-instruction mnemonics to forbid on x86.
# (AVX/BMI/FMA families + a few others that imply >baseline SSE2):
X86_BAD='\b(vfmadd|vfnmadd|vfmsub|vfnmsub|andn|bextr|blsi|blsmsk|blsr|bzhi|mulx|pdep|pext|rorx|sarx|shlx|shrx|tzcnt|popcnt|adcx|adox|pclmulqdq|vpaddd|vpsubd|vpmulld|vpmullw|vmovdqa|vmovdqu|vpshufd|vextracti128|vinserti128|vpermilps|vbroadcastss|vpbroadcastd|aes(enc|dec)|vaes|sha1|sha256|kmovd|vpclmulqdq)\b'
ARM64_BAD='^$'

case "$MARCH" in
  x86-64)  BAD="$X86_BAD" ;;
  aarch64|arm64) BAD="$ARM64_BAD" ;;
  *) echo "audit-baseline: unknown arch '${MARCH:-}'; cannot guarantee baseline" >&2; exit 3 ;;
esac

# Disassemble all executable sections.
if [ "$DIS" = "dumpbin" ]; then
    DISASM="$(dumpbin /DISASM "$BINARY" 2>/dev/null || true)"
else
    if [ "$MARCH" = "x86-64" ]; then
        DISASM="$("$DIS" -d --disassembler-options=att "$BINARY" 2>/dev/null || true)"
    else
        DISASM="$("$DIS" -d "$BINARY" 2>/dev/null || true)"
    fi
fi

if [ -z "$DISASM" ]; then
    echo "ERROR: disassembler produced no output for $BINARY" >&2
    exit 2
fi

MATCHES="$(grep -oE "$BAD" <<<"$DISASM" | sort -u || true)"

if [ -n "$MATCHES" ]; then
    echo "audit-baseline: FAIL — advanced instructions found in $BINARY:"
    echo "$MATCHES"
    exit 1
else
    echo "audit-baseline: PASS — no advanced (AVX/BMI/FMA) instructions in $BINARY"
    exit 0
fi