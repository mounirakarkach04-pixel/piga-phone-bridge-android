# A7SEM Reverse Release Flow

Before a material build:

`release target → reverse plan → preconditions → dependencies → proof obligations → fresh forward admission → build → verify`

After a failed verification:

`freeze affected branch → reverse trace → earliest admissibility divergence → repair/delay/block → material-change re-entry → fresh admission → verify`

Blind reruns are prohibited. A local CI delay does not grant authority and does not convert missing evidence into success.
