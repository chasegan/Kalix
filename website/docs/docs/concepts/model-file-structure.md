---
title: "[kalix]"
---

# [kalix]

Kalix uses an INI format for model definitions. The main model file starts with [kalix] section, which can be used to define global model parameters, followed by sections such as: [input] for defining input datasets, [outputs] for defining output fields, and [node.####] for defining model nodes.

# The [kalix] section

The [kalix] section provides a place to set the version (optional), simulation period (optional), and the execution\_order method (also optional).

```toml
[kalix]
version = 0.1.2
start = 1889-07-01
end = 2020-06-30
```

The **version**, if provided, must match the kalix version in order to run. This is a safety mechanism to prevent models from being accidentally run in the wrong version of the software. If the version is omitted, kalix will try to run the model. It’s up to you if/how you use the version parameter.

The **start** and **end** parameters are optional (can use either, none, or both) ways to set the simulation period. The values specify the first and last simulation step\*\*. If these are not provided the simulation period is automatically determined based on the available input data. The start and end parameters will accept dates in a number of formats including “YYYY-MM-DD” or ISO formats. *[\*\*Technically the value represents the beginning of the step. The line “end = 2020-06-30” is parsed to a value of “2020-06-30T00:00:00” being the beginning of the final simulation step in this example.]*
