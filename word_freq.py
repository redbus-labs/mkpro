import re
from collections import Counter

text = "gx3 and 4x6"
words = re.findall(r'[a-zA-Z0-9]+', text)
print(dict(Counter(words)))
