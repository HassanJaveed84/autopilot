| Scenario | Fields | Canonical URL chars | Tolerant URL chars | v2 parses | 0.2.0 parser says |
|---|---|---|---|---|---|
| 1 perfume step, 3 paragraphs, product URL | 9 | 697 | 665 | yes | NEWER_VERSION |
| 3 rich steps (gift plan) | 15 | 1045 | 977 | yes | NEWER_VERSION |
| Research step, 2 paragraphs, 5 URLs | 12 | 1096 | 1002 | yes | NEWER_VERSION |
| Plain v1 (1 decision, 3 steps, 1 note) | 8 | 331 | 325 | yes | Valid |
| Mixed: 1 d, 1 n, 3 rich + 3 plain steps | 20 | 1257 | 1183 | yes | NEWER_VERSION |
| Worst case allowed by the item caps | 114 | 31193 | 30793 | yes | TOO_BIG |
