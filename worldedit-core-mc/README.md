# Minecraft platform layer

This module adapts the WorldEdit 7.4.6 Minecraft platform layer to FAWE's core for
Minecraft 26.3. It contains world/entity/item adapters, registry and property
conversion, NBT and data fixing, and the shared native mixins.

Fabric startup, permissions, events, and FAWE scheduling live in `worldedit-fabric`.
Minecraft ships unobfuscated classes; this module uses the non-remapping Loom plugin
and a Java 25 toolchain.

Native access is dispatched to the owning server thread. The platform advertises that
FAWE native chunk placement is unavailable, so edit sessions use individual block
access with normal history. See [the Fabric guide](../docs/FABRIC-26.3.md) for build,
runtime validation, and compatibility limits.
