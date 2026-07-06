---
title: "Commandline"
---

# Commandline

The commandline executable, “kalix”, can be used to run models, calibrations, and do other modelling tasks from the commandline.

|  |  |
| --- | --- |
| **Usage:** | kalix <COMMAND> |
| **Commands:** |  |
| help | Shows this help, or help for the given subcommand(s) |
| simulate [alias: sim] | Run simulation (use “kalix help simulate” to see usage) |
| optimise [alias: opt] | Run optimisation (use “kalix help optimise” to see usage) |
| new-session | Open a new interactive session. In this “session” mode, the kalix engine engages in two-way communicating over STDIO using a bespoke protocol. This was primarily developed for background communication between the kalix engine and kalixide. |
| get-api | Returns this commandline API specification as JSON |
| **Options:** |  |
| -h, --help | Print help |
| -v, --version | Print the current kalix version |

Examples:

|  |
| --- |
| > kalix help |
| > kalix get-api |
| > kalix new-session |

## Subcommands

#### Simulate [alias: sim]

*(use “kalix help simulate” in a terminal to see this):*

|  |  |
| --- | --- |
| **Usage:** | kalix simulate <MODEL\_FILE> [OPTIONS] |
| **Arguments:** |  |
| <MODEL\_FILE> | Path to the model file |
| **Options:** |  |
| -o, --output-file <OUTPUT\_FILE> | File where you want the model results to be saved |
| -p, --profile | Performance profiling figures reported to the console |
| -m, --mass-balance <MASS\_BALANCE> | File where you want the mass balance report to be saved |
| -v, --verify-mass-balance <VERIFY\_MASS\_BALANCE> | File of existing mass balance report if you want to verify no change |
| -h, --help | Print help |

Examples:

|  |
| --- |
| > kalix simulate my\_model.ini -o results.csv |

#### Optimise [alias: opt]

*(use “kalix help optimise” in a terminal to see this):*

|  |  |
| --- | --- |
| **Usage:** | kalix optimise <CONFIG\_FILE> [MODEL\_FILE] [OPTIONS] |
| **Arguments:** |  |
| <CONFIG\_FILE> | Path to the optimisation configuration file |
| [MODEL\_FILE] | Path to the model file. It is also possible to specify the model file in the optimisation config, therefore this argument is optional. |
| **Options:** |  |
| -s, --save-model <SAVE\_MODEL> | Path to save the optimized model file (.ini) |
| -h, --help | Print help |

Examples:

|  |
| --- |
| > kalix optimise config.ini model.ini -s calibrated\_model.ini |
