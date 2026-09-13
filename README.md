# Freyja✨
## Samsung Flash Tool into a android app.

**Freyja** is a **Samsung Flashing Tool** specifically made for flashing a Samsung device from another android device.

## About Freyja
**Freyja** is designed to be a open-source app just like her older brother's Odin and Heimdall, in the past we've seen Odin (specifically made for **Windows**) and we've seen Heimdall (porting Odin to **Linux**). I've used Heimdall's backend code (mostly C++) to implement the logic towards Freyja to make the project a little bit more easy.

## Why using Freyja?
Normally when you try flashing a custom recovery or even a custom super and boot we needed to have a **PC** running the operating system first off **Windows** and later on **Linux** since we've implemented the code of **Heimdall** into our **own application** you only need a other **android device** with a correct **cable** connection to eachother.

## Key features of Freyja
**True Mobile Independence:** Fix or flash Samsung devices on the go using just a phone-to-phone USB-OTG connection.
**Native C++ Backend:** Executes fast, low-level partition data transfers and binary parsing.
**Comprehensive Partition Slots:** Dedicated support for --BOOT, --RECOVERY, --SUPER, --DTBO, --VBMETA, and --PIT.
**Advanced Control Toggles:** Built-in Verbose mode for real-time debugging logs and a No reboot safety option.

## How It Works
1. Turn on **USB-OTG** in the settings of your host Android.
2. **Connect** your target Samsung device (booted into Download Mode) to your host Android running Freyja via a USB-OTG cable.
3. **Select** your required **partition images.**
4. Tap **START** and let the native engine handle the raw USB bulk transfers.

## Credits
luk1337 and Benjamin-Dobell: [Heimdall](https://github.com/Benjamin-Dobell/Heimdall)

## Disclaimer
This tool interacts **directly** with **critical low-level device partitions**. Flashing **custom binaries** carries **inherent risks** of data loss or bricking. Use with **caution**, **know** what you are flashing, and always **back up** your data.