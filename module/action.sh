# Runs when the module's Action button is pressed in the Magisk app (Magisk 28+):
# makes the phone match the profile now, and shows what it did.
MODDIR=${0%/*}

sh "$MODDIR/apply.sh" action
