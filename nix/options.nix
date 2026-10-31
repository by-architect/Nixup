# The options a phone config can set. A config that sets anything else, or a
# value of the wrong type, stops the build with an error.
{ lib, ... }:
{
  options.android.darkMode = lib.mkOption {
    type = lib.types.nullOr lib.types.bool;
    default = null;
    description = "Dark mode on (true) or off (false); null leaves it as the user sets it.";
  };
}
