# Hubitat driver - Daikin WiFi Split System

Forked to suit Daikin BRP15B61 Wireless LAN Adapter
- changed /aircon/ endpoints to /skyfi/aircon/
- changed some field mappings

# Original Readme

Add driver code from daikin-wifi-split-system-hubitat.groovy to hub and add new device type Daikin WiFi Split System Hubitat.

Settings should be self-explanatory. Be careful using refresh interval 1 minute, its barely supported.

To create a temp sensor for outside temp, add a virtual temp sensor and update its value in rulemachine using the "outsideTemp" attribute value *changed* event and custom action setTemperature to string %value%.

This integration require a B series WIFI module, the new cloud connected C series modules will NOT work with this integration as they do not respond on the network endpoints this integration use.
You can find out the series of module you have by checking the letter after BRP069, its either B or C, you need B for this integration.

See daikinapi.md for info about the AC api calls you can make if you want to modify the driver.
