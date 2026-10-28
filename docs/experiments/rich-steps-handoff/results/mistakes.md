| Model output | Result |
|---|---|
| label + raw https:// | accepted: [Product page] https://www.chanel.com/us/p/107460/ |
| label + fully encoded URL | accepted: [Product page] https://www.chanel.com/us/p/107460/ |
| URL only, no label | accepted: [null] https://www.chanel.com/us/p/107460/ |
| ChatGPT citation ?utm_source=chatgpt.com raw = | accepted: [Chanel] https://www.chanel.com/us/p/107460/?utm_source=chatgpt.com |
| query with %26 (correct) | accepted: [Shop] https://shop.example.com/p?size=50ml&color=blue |
| query with raw & (mistake) | refused: UNKNOWN_FIELD |
| URL path %20 not re-encoded (mistake) | refused: BAD_LINK |
| URL path %2520 (correct) | accepted: [Form] https://example.gov/forms/DS%2082.pdf |
| raw + in label (mistake) | refused: AMBIGUOUS_PLUS |
| sl before any s | refused: DETAIL_WITHOUT_STEP |
| sd after a note, not right after its step | refused: DETAIL_WITHOUT_STEP |
| link cut short (no end=1) | refused: INCOMPLETE |
| end=1 not last | refused: DUPLICATE_FIELD |
| javascript: URL | refused: BAD_LINK |
| intent: URL | refused: DISALLOWED_CHARACTER |
| user-info spoof | refused: BAD_LINK |
| no scheme | refused: BAD_LINK |
| http (not https) | accepted: [Old site] http://example.org/page |
| 6 links on one step | refused: TOO_MANY_ITEMS |
| 5 description paragraphs | refused: TOO_MANY_ITEMS |
| sd with %0A line break | refused: DISALLOWED_CHARACTER |
| raw # anchor in URL (mistake) | refused: NOT_A_LINK (java.net.URI rejects a second #) |
