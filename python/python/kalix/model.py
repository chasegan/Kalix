"""Python wrapping of Rust backend.
"""
from __future__ import annotations

from kalix._native import Model, load_file, load_string, new_model

__all__ = ["Model", "load_file", "load_string", "new_model"]
