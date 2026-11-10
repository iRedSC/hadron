# Hadron

Bukkit-shaped plugins for NeoForge servers, without a hybrid. Plugins are jars in `plugins/` that reload while the server runs, and everything they registered is undone for them. Built so an agent can write one from "make me a Hadron plugin for X".

Works today, inside this repo: the plugin host with hot reload, Hadron's first API and events, LuckPerms permissions, watched areas and the headless test harness. Not yet: the Gradle plugin and agent skill that let a project outside this repo use it, bundled plugins beyond LuckPerms, mod bridges, and Minecraft versions other than 1.21.1. See [docs/design.md](docs/design.md), "Order of work".

Targets NeoForge 21.1 (Minecraft 1.21.1).
