---
title: "Running Python Notebooks with UV"
---

# Running Python Notebooks with UV

This guide walks you through setting up and running Python notebooks (from the Kalix Tutorials) in VSCode on Windows, using [UV](https://docs.astral.sh/uv/) to manage the environment. Code autocomplete will work once you're done.

### 1) Install UV and VSCode (once per machine)

- Install UV by running this in PowerShell, then close and reopen PowerShell so it's on your path:

```powershell
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
```

- Install VSCode, and in its Extensions panel (`Ctrl+Shift+X`) add the **Python** extension by Microsoft (this bundles the Jupyter extension too).

### 2) Set up an environment for the KalixTutorials project

- In VSCode, `File → Open Folder` and open the **top-level** Kalix Tutorials folder.

- Open a terminal (`Terminal → New Terminal`) and confirm you're in the project root.

- Create the environment:

```powershell
uv venv
```

- Install the packages:

```powershell
uv pip install --python .venv\Scripts\python.exe kalix ipykernel matplotlib
```

### 3) Run a notebook

- Open a notebook (`.ipynb`) and click the kernel selector in the top-right corner.

- Choose **Python Environments** and pick the `.venv` inside your project folder.

- If it doesn't appear, use `Ctrl+Shift+P` → **Python: Select Interpreter** → **Enter interpreter path...** and browse to `.venv\Scripts\python.exe`.

- Run a cell with `Shift+Enter` — code execution and autocomplete will both work.

### Troubleshooting

- **"Running cells requires ipykernel"** — `ipykernel` isn't in the environment VSCode selected. Re-run the install command, making sure it targets your project's local `.venv` and not a `.venv` under `C:\Users\...`.

- **Autocomplete isn't working** — VSCode's language server is pointed at the wrong interpreter. Use `Ctrl+Shift+P` → **Python: Select Interpreter** and pick the project's `.venv\Scripts\python.exe`, then reload with `Ctrl+Shift+P` → **Developer: Reload Window**. Make sure `kalix` actually installed by checking `uv pip list --python .venv\Scripts\python.exe`.
