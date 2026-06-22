# Caesar

Caesar is a simple Spigot/Paper plugin for Minecraft server staff.

The plugin saves some information about players when they join the server, such as their IP address and client brand, and allows staff members to check for possible alternative accounts.

## Commands

* `/alts <player>` - shows possible alt accounts based on IP address
* `/check <player>` - shows saved information about a player
* `/staffmode` - enables or disables staff mode

## Permissions

* `caesar.alts`
* `caesar.check`
* `caesar.staff`

## Installation

Build the plugin with Maven:

```bash
mvn clean package
```

Then place the generated `.jar` file inside the server's `plugins` folder and restart the server.
