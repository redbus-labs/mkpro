import sys
from collections import Counter

def count_chars(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        counts = Counter(content)
        
        for char, count in counts.most_common():
            try:
                print(f"{repr(char)}: {count}")
            except UnicodeEncodeError:
                # Fallback for characters that the console can't display
                encoding = sys.stdout.encoding or 'ascii'
                safe_char = char.encode(encoding, errors='replace').decode(encoding)
                print(f"{repr(safe_char)} (u{ord(char):04x}): {count}")
                
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python char_frequency.py <file_path>")
    else:
        count_chars(sys.argv[1])
