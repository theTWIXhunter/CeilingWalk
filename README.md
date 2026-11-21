# Ceiling walk (Reversed Gravity) by theTWIXhunter

This plugin allows you to flip gravity and your player(s) upside down!
*DOWNLOAD ON MODRINTH:* [Click here](https://modrinth.com/plugin/ceilingwalk/)

You can choose if you want players to be able to switch between the ground and the ceiling or if you want your complete world to be upside-down at all times.

### ⚠️ RESOURCE PACK: [Download Here](https://modrinth.com/plugin/ceilingwalk/version/1) - required to push the upside-down post effect

## Features:
- Toggle reversed gravity and ceiling walking at any time
- Native upside-down mannequin with player skin, armor, held items, crouch pose, and arm swings
- F5-mode for showing the mannequin to the owner
- Optional auto-jump, persistent per-player settings, and vanilla-like crouch movement
- Creative/Spectator and mouse-inversion warnings
- Persistent `/cw hide-mouse-warning` setting
- CraftEngine resource-pack compatibility
- Configurable and translatable chat messages

## Commands:
- /cw (toggle ceiling walk on and off)
- /cw f5-mode (toggle an upside-down manequin)
- /cw autojump (toggle autojump on and off)
- /cw hide-mouse-warning [true|false] (toggle the mouse inversion warning)
- /cw reload (reload config.yml and data.yml)
- /cw help (show this help message)
- /cw version (show version info)
- /cw about (show about info)
- /cw toggle (alias of the main /cw)

## Config:
```
# +--------------------------------------------------------------------------+
# |    ------====== CEILING WALK (Reversed Gravity) PLUGIN ======------      |
# |                       --- By TheTWIXhunter ---                           |
# +--------------------------------------------------------------------------+
# |                                                                          |                                          |
# | More information about this config file can be found here:               |
# | https://thetwixhunter.nekoweb.org/ceilingwalk/guides/configuration.html  |
# |                                                                          |
# | !!!!      --- THIS PLUGIN BUNDELS WITH A RESOURCE PACK ---          !!!! |
# |  The resource pack can be found at:                                      |
# |  https://modrinth.com/plugin/ceilingwalk/version/1                       |
# |                                                                          |
# |  and can also be found in the plugin's data folder (/plugins/CeilingWalk)|
# |  It is strongly recommended to use the resource pack for the post-effect |
# |  to work properly, if you don't use the pack then your screen won't turn.|                                                       |
# +--------------------------------------------------------------------------+


# Gravity attribute while ceiling walking. Vanilla default is 0.08.
# Negative = you fall upward and land on ceilings.
gravity: -0.08


# Log right-click and block-place diagnostics to the server console.
debug-placement: false

# Post effect that flips the screen (needs the upside_down resource pack on the client).
# If you don't use the resource pack, you can set this to "none" to disable the post effect.
post-effect: "upsidedown:flip"

# Show the mannequin to its owner by default when using /cw f5-mode.
f5-mode-default: false

# Enable auto-jump for new players by default.
auto-jump-default: true

# Show the mouse inversion warning whenever a player toggles /cw.
# Players can hide it permanently with /cw hide-mouse-warning.
mouse-warning:
  enabled: true

# Warn players when toggling /cw in Creative or Spectator mode.
creative-warning:
  enabled: true

craft-engine-compat:
  # Add this plugin's resource pack to CraftEngine's external zip merge list.
  enabled: true
  # Location relative to the server root, as used by CraftEngine.
  resource-pack-location: "CeilingWalk/CeilingWalk-resourcepack.zip"
  # Location of CraftEngine's configuration file.
  craft-engine-config-location: "plugins/CraftEngine/config.yml"

messages:
# Every message string is translatable here but it's too much to display here.
```


