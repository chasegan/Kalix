# Text Editor Enhancement Plan

## Overview
This document outlines the development plan for enhancing the Kalix GUI text editor panel to behave like a high-quality code editor.

## Requirements Summary
- **Priority**: Focus on basics first with selected advanced features
- **File Support**: Single file editing (no tabs)
- **Syntax**: INI/TOML highlighting and validation
- **Validation**: Extensible system for future Kalix-specific rules
- **Auto-completion**: Not needed initially

## Development Phases

### Phase 1: Foundation & Core Features
1. **Enhanced Text Editor Component**
   - Replace basic JTextArea with custom editor supporting advanced features
   - Implement proper monospace font (JetBrains Mono, Fira Code, or Consolas)
   - Add dirty file indicator (* in title/status)
   - Better undo/redo with proper history stack

2. **Line Numbers Panel**
   - Toggleable line numbers (Ctrl+L)
   - Proper alignment and styling
   - Current line highlighting

### Phase 2: Visual Enhancements
3. **Selection & Highlighting**
   - Highlight all instances of selected text
   - Current line highlighting
   - Bracket/parentheses matching and highlighting
   - Quote matching and highlighting

4. **Basic Syntax Highlighting**
   - INI/TOML section headers `[section]`
   - Property names vs values with different colors
   - Comments with distinct styling
   - Strings and numbers

### Phase 3: Advanced Features
5. **Find & Replace**
   - Find dialog (Ctrl+F)
   - Find and replace dialog (Ctrl+H)
   - Find next/previous navigation

6. **Navigation & Productivity**
   - Go to line dialog (Ctrl+G)
   - Recent files list in File menu
   - Word wrap toggle

### Phase 4: Validation System
7. **Extensible Validation Framework**
   - Real-time INI/TOML syntax validation
   - Error highlighting with tooltips
   - Extensible architecture for future Kalix-specific validation rules

## High-Priority Features (from user feedback)
- Dirty file indicator
- Highlight all instances of selected text
- Recent files in file menu
- Enhanced undo/redo
- Go to line
- Bracket matching/highlighting
- Quotation mark matching/highlighting
- Real-time INI/TOML validation (extensible)
- Syntax highlighting: sections, property names vs values

## Implementation Notes
- Start with Phase 1 to establish foundation
- Each phase builds progressively toward professional editor experience
- Maintain extensibility for future Kalix-specific enhancements
- Focus on user experience and performance