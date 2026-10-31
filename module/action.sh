# Runs when the module's Action button is pressed in the Magisk app (Magisk 28+):
# builds the phone config and applies it, like the app's Build button.
MODDIR=${0%/*}

sh "$MODDIR/build.sh" action
