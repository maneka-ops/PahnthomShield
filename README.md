# PhantomShield

A custom Paper plugin for **personal, timed phantom protection**. Designed for the Arcane World Paper server (Paper 26.2, Java 25).

## Features

- Right-click a marked Phantom Shield Key to activate protection.
- Configurable protection duration (default: 30 minutes).
- Cancels phantom targeting and phantom damage against protected players.
- Cancels phantom spawn events within a configurable radius of protected players.
- Existing phantoms targeting a protected player have their target cleared.
- Persists expiry timestamps across restarts by default.
- Admin commands to give keys, activate/revoke protection, check status, and reload config.
- Protection is per-player; it does not change the world-wide `doInsomnia` gamerule.

## Requirements

- Paper 26.2 (or compatible Paper build matching the API dependency)
- Java 25
- Maven 3.9+

## Build

From this project directory:

```bash
mvn clean package
```

The plugin jar will be created at:

```text
target/PhantomShield-1.0.0.jar
```

Copy that jar into the server's `plugins/` directory, then restart the server.

## Commands

- `/phantomshield give <player> [amount] [minutes]`
- `/phantomshield activate <player> [minutes]`
- `/phantomshield status [player]`
- `/phantomshield list`
- `/phantomshield revoke <player>`
- `/phantomshield reload`

Alias: `/pshield`

## Permissions

- `phantomshield.use` — allowed by default; key activation is available to all players.
- `phantomshield.admin` — operator-only by default.

## Configuration

Edit `plugins/PhantomShield/config.yml`, then run `/phantomshield reload`.

- `settings.default-duration-minutes`: protection length.
- `settings.phantom-protection.spawn-suppression-radius`: spawn-event suppression radius.
- `settings.phantom-protection.suppress-natural-spawning-near-player`: enable/disable spawn suppression.
- `settings.persist-after-restart`: persist active protection.

## Notes

Spawn suppression works by cancelling phantom spawn events whose spawn location is within the configured radius of a protected player. Bukkit/Paper spawn events do not always expose whether a phantom was spawned naturally or by a plugin, so this implementation suppresses **all phantom spawn events** inside that radius while protection is active. Phantoms outside the radius and all other players remain unaffected.

Protection is based on the player's UUID and wall-clock expiry timestamp, so logging out does not pause the timer.
