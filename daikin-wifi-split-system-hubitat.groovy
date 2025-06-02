/**
 *  Daikin WiFi BRP15B61 (Custom, no built-in Thermostat capability)
 *
 *  - Uses only: Temperature Measurement, Switch, Sensor, Refresh, Polling.
 *  - Custom attributes: heatingSetpoint, coolingSetpoint, plenumTemperature,
 *      currMode, fanRate, statusText, connection.
 *  - Custom commands: heat(), cool(), dry(), fan(), tempUp(), tempDown(),
 *      setHeatingSetpoint(°), setCoolingSetpoint(°), setMode(String), setFanRate(String).
 *
 *  Always sends:
 *    pow=<0|1>&mode=<0|1|2|7>&stemp=<°C>&shum=0&f_rate=<0|1|3|5>&f_dir=0
 *
 *  Copyright 2018 Ben Dews – https://bendews.com
 *  Licensed under the MIT License.
 */

import groovy.transform.Field

@Field final Map DAIKIN_MODES = [
    "1":   "heat",
    "2":   "cool",
    "7":   "dry",
    "0":   "fan",
    "off": "off"
]

@Field final Map DAIKIN_FAN_RATE = [
    "0": "auto",
    "1": "Low",
    "3": "Medium",
    "5": "High"
]

metadata {
    definition(
        name:      "Daikin WiFi BRP15B61 (No Thermostat)",
        namespace: "mejtoogood",
        author:    "mejtoogood"
    ) {
        capability "Temperature Measurement"
        capability "Switch"
        capability "Sensor"
        capability "Refresh"
        capability "Polling"

        attribute "plenumTemperature",  "number"
        attribute "heatingSetpoint",    "number"
        attribute "coolingSetpoint",    "number"
        attribute "currMode",           "string"
        attribute "fanRate",            "string"
        attribute "statusText",         "string"
        attribute "connection",         "string"

        command "heat"
        command "cool"
        command "dry"
        command "fan"
        command "off"
        command "tempUp"
        command "tempDown"
        command "setHeatingSetpoint",   ["number"]
        command "setCoolingSetpoint",   ["number"]
        command "setMode", [[
            name:        "mode*",
            type:        "ENUM",
            description: "Mode to set",
            constraints: ["heat", "cool", "dry", "fan", "off"]
        ]]
        command "setFanRate", [[
            name:        "fanRate*",
            type:        "ENUM",
            description: "Fan Rate",
            constraints: ["auto", "Low", "Medium", "High"]
        ]]
    }

    preferences {
        input("ipAddress",         "string",
              title: "Daikin WiFi IP Address",
              required: true, displayDuringSetup: true)
        input("ipPort",            "string",
              title: "Daikin WiFi Port (default: 80)",
              defaultValue: "80", required: true, displayDuringSetup: true)
        input("refreshInterval",   "enum",
              title: "Refresh Interval (minutes)",
              defaultValue: "10",
              options: ["1","5","10","15","30"],
              required: true, displayDuringSetup: true)
        input("displayFahrenheit", "boolean",
              title: "Display Fahrenheit?",
              defaultValue: false, displayDuringSetup: true)
        input(name: "debugLogging", type: "bool",
              defaultValue: false,
              submitOnChange: true,
              title: "Enable debug logging")
    }
}

//─────────────────────────────────────────────────────────────────────────────
//   GENERIC HELPERS
//─────────────────────────────────────────────────────────────────────────────

private getHostAddress() {
    return "${settings.ipAddress}:${settings.ipPort}"
}

private getDNI(String ipAddress, String port) {
    logDebug "Generating DNI from ${ipAddress}:${port}"
    String ipHex   = ipAddress.tokenize('.')
                              .collect { String.format('%02X', it.toInteger()) }
                              .join()
    String portHex = String.format('%04X', port.toInteger())
    return "${ipHex}:${portHex}"
}

private apiGet(String apiCommand) {
    logDebug "→ HTTP GET: http://${getHostAddress()}${apiCommand}"
    sendEvent(name: "connection", value: "local", displayed: false)
    def ha = new hubitat.device.HubAction(
        method: "GET",
        path:   apiCommand,
        headers: [ Host: getHostAddress() ]
    )
    sendHubCommand(ha)
}

private convertTemp(Double temp, Boolean isFahrenheit) {
    logDebug "Converting ${temp} (isF=${isFahrenheit})"
    if (isFahrenheit) {
        // F→C
        return (((temp - 32) * 5) / 9).round()
    }
    // C→F
    return (((temp * 9) / 5) + 32).round()
}

private parseTemp(Double temp, String method) {
    logDebug "${method}-ing ${temp}"
    if (settings.displayFahrenheit.toBoolean()) {
        switch(method) {
            case "GET": return convertTemp(temp, false)
            case "SET": return convertTemp(temp, true)
        }
    }
    return temp
}

//─────────────────────────────────────────────────────────────────────────────
//   DAIKIN-SPECIFIC HELPERS
//─────────────────────────────────────────────────────────────────────────────

private parseDaikinResp(String response) {
    def map = [:]
    response.split(",").each { token ->
        def pair = token.split("=")
        if (pair.length == 2) {
            map[pair[0]] = pair[1]
        }
    }
    return map
}

///
/// Build the set_control_info URL with all required params:
///   pow=<0|1>, mode=<0|1|2|7>, stemp=<°C>, shum=0, f_rate=<0|1|3|5>, f_dir=0
///
/// We always read “currMode” (which was just updated by the caller via sendEvent),
/// then map it back to the numeric key.  The one‐second delay (runIn(1,...)) gives
/// Hubitat time to commit the sendEvent(name:"currMode", ...) before we read it here.
///
private updateDaikinDevice(Boolean turnOff = false) {
    def pow   = "?pow=1"
    def mode  = "&mode=1"
    def sTemp = "&stemp=21"
    def fRate = "&f_rate=0"
    def sHum  = "&shum=0"
    def fDir  = "&f_dir=0"

    // Always read currMode, since heat()/cool()/fan()/etc. did sendEvent("currMode",...)
    def currentMode = device.currentValue("currMode")
    def modeKey     = DAIKIN_MODES.find { it.value == currentMode }?.key

    // Fan rate (default to "0"→auto if nothing set yet)
    def fanRateVal  = device.currentValue("fanRate")
    def fanKey      = DAIKIN_FAN_RATE.find { it.value == fanRateVal }?.key ?: "0"

    // If mode is heat/cool, pick the correct setpoint.  Otherwise leave targetVal null
    Double targetVal = null
    if (currentMode == "heat") {
        def raw = device.currentValue("heatingSetpoint")
        if (raw != null) {
            targetVal = parseTemp(raw.toDouble(), "SET")
        }
    }
    else if (currentMode == "cool") {
        def raw = device.currentValue("coolingSetpoint")
        if (raw != null) {
            targetVal = parseTemp(raw.toDouble(), "SET")
        }
    }

    if (turnOff) {
        pow = "?pow=0"
    }
    if (modeKey) {
        mode = "&mode=${modeKey}"
    }
    if (targetVal != null) {
        sTemp = "&stemp=${targetVal}"
    }
    if (fanKey) {
        fRate = "&f_rate=${fanKey}"
    }

    def url = "/skyfi/aircon/set_control_info${pow}${mode}${sTemp}${sHum}${fRate}${fDir}"
    logDebug "updateDaikinDevice(): turnOff=${turnOff}, modeKey=${modeKey}, set=${targetVal}, fanKey=${fanKey} → ${url}"
    apiGet(url)

    runIn(2, "pollControlInfo")
    runIn(4, "pollSensorInfo")
}

def pollControlInfo() {
    apiGet("/skyfi/aircon/get_control_info")
}

def pollSensorInfo() {
    apiGet("/skyfi/aircon/get_sensor_info")
}

//─────────────────────────────────────────────────────────────────────────────
//   LIFECYCLE & SCHEDULE
//─────────────────────────────────────────────────────────────────────────────

private startScheduledRefresh() {
    logDebug "startScheduledRefresh()"
    def mins = (settings.refreshInterval?.toInteger() ?: 10)
    logDebug "Scheduling refresh every ${mins} min"
    if (mins == 1) {
        runEvery1Minute(refresh)
    } else {
        "runEvery${mins}Minutes"(refresh)
    }
}

def installed() {
    logDebug "installed()"
    // Seed a few attributes so UI isn’t totally blank:
    sendEvent(name: "heatingSetpoint", value: 20, displayed: false)
    sendEvent(name: "coolingSetpoint", value: 21, displayed: false)
    sendEvent(name: "plenumTemperature", value: null, displayed: false)
    sendEvent(name: "currMode", value: null, displayed: false)
    sendEvent(name: "fanRate", value: "auto", displayed: false)
    sendEvent(name: "statusText", value: "idle", displayed: false)
}

def updated() {
    logDebug "updated() → ${settings}"
    if (!state.updated || now() >= state.updated + 5000) {
        unschedule()
        runIn(1, "setDNI")
        runIn(5, "refresh")
        startScheduledRefresh()
    }
    state.updated = now()
}

def setDNI() {
    logDebug "Setting DNI"
    String newDNI = getDNI(settings.ipAddress, settings.ipPort)
    device.setDeviceNetworkId(newDNI)
}

def refresh() {
    logDebug "refresh()"
    runIn(2, "pollSensorInfo")
    runIn(4, "pollControlInfo")
}

def poll() {
    logDebug "poll() called"
    refresh()
}

//─────────────────────────────────────────────────────────────────────────────
//   PARSE & ATTRIBUTE UPDATES
//─────────────────────────────────────────────────────────────────────────────

def parse(String description) {
    def msg        = parseLanMessage(description)
    def body       = msg.body
    def daikinResp = parseDaikinResp(body)
    logDebug "Parsing Daikin response: ${daikinResp}"

    def events    = []
    def turnedOff = false

    def devicePower      = daikinResp["pow"]
    def deviceMode       = daikinResp["mode"]
    def deviceInsideTemp = daikinResp["htemp"]
    def deviceSetTemp    = daikinResp["stemp"]
    def deviceFanRate    = daikinResp["f_rate"]

    // 1) Power
    if (devicePower == "0") {
        turnedOff = true
        events << createEvent(name: "currMode", value: "off")
        events << createEvent(name: "statusText", value: "off")
        events << createEvent(name: "switch", value: "off")
    }

    // 2) Mode
    if (deviceMode) {
        def m = DAIKIN_MODES[deviceMode]
        events << createEvent(name: "currMode", value: m)
        events << createEvent(name: "statusText", value: m)
        events << createEvent(name: "switch", value: "on")
    }

    // 3) Inside temperature → push into “temperature” & “plenumTemperature”
    if (deviceInsideTemp) {
        def insideC = Double.parseDouble(deviceInsideTemp)
        def readTemp = parseTemp(insideC, "GET")
        events << createEvent(name: "temperature",      value: readTemp)
        events << createEvent(name: "plenumTemperature", value: readTemp)
    }

    // 4) Setpoint → store in heatingSetpoint or coolingSetpoint depending on parsed mode
    if (deviceSetTemp) {
        def raw    = Double.parseDouble(deviceSetTemp)
        def setVal = parseTemp(raw, "GET")
        // Use the freshly‐parsed mode (not device.currentValue)
        def mKey   = DAIKIN_MODES[deviceMode]
        if (mKey == "heat") {
            events << createEvent(name: "heatingSetpoint", value: setVal)
        }
        else if (mKey == "cool") {
            events << createEvent(name: "coolingSetpoint", value: setVal)
        }
    }

    // 5) Fan‐rate support
    if (deviceFanRate) {
        events << createEvent(name: "fanRate", value: DAIKIN_FAN_RATE[deviceFanRate])
    }

    return events
}

//─────────────────────────────────────────────────────────────────────────────
//   USER COMMANDS (mode, setpoints, fan, tempUp/Down)
//─────────────────────────────────────────────────────────────────────────────

def on() {
    // “Switch:on” means “resume whatever currMode is already set to.”
    logDebug "on(): currMode=${device.currentValue("currMode")}"
    runIn(1, "updateDaikinDevice", [data: false])
}

def off() {
    logDebug "off()"
    sendEvent(name: "currMode", value: "off")
    runIn(1, "updateDaikinDevice", [data: true])
}

def heat() {
    logDebug "heat()"
    sendEvent(name: "currMode", value: "heat")
    runIn(1, "updateDaikinDevice", [data: false])
}

def cool() {
    logDebug "cool()"
    sendEvent(name: "currMode", value: "cool")
    runIn(1, "updateDaikinDevice", [data: false])
}

def dry() {
    logDebug "dry()"
    sendEvent(name: "currMode", value: "dry")
    runIn(1, "updateDaikinDevice", [data: false])
}

def fan() {
    logDebug "fan()"
    sendEvent(name: "currMode", value: "fan")
    runIn(1, "updateDaikinDevice", [data: false])
}

def setMode(String m) {
    logDebug "setMode(${m})"
    sendEvent(name: "currMode", value: m)
    runIn(1, "updateDaikinDevice", [data: false])
}

def setHeatingSetpoint(Double value) {
    logDebug "setHeatingSetpoint(${value})"
    sendEvent(name: "heatingSetpoint", value: value)
    sendEvent(name: "currMode", value: "heat")
    runIn(1, "updateDaikinDevice", [data: false])
}

def setCoolingSetpoint(Double value) {
    logDebug "setCoolingSetpoint(${value})"
    sendEvent(name: "coolingSetpoint", value: value)
    sendEvent(name: "currMode", value: "cool")
    runIn(1, "updateDaikinDevice", [data: false])
}

def setFanRate(String rate) {
    logDebug "setFanRate(${rate})"
    sendEvent(name: "fanRate", value: rate)
    runIn(1, "updateDaikinDevice", [data: false])
}

def tempUp() {
    logDebug "tempUp(): currMode=${device.currentValue("currMode")}"
    String m = device.currentValue("currMode")
    switch(m) {
        case "heat":
            Double hsp = device.currentValue("heatingSetpoint") ?: 20
            setHeatingSetpoint(hsp + 1)
            break
        case "cool":
            Double csp = device.currentValue("coolingSetpoint") ?: 21
            setCoolingSetpoint(csp + 1)
            break
        default:
            logDebug "tempUp(): no setpoint to bump when mode=$m"
            break
    }
}

def tempDown() {
    logDebug "tempDown(): currMode=${device.currentValue("currMode")}"
    String m = device.currentValue("currMode")
    switch(m) {
        case "heat":
            Double hsp = device.currentValue("heatingSetpoint") ?: 20
            setHeatingSetpoint(hsp - 1)
            break
        case "cool":
            Double csp = device.currentValue("coolingSetpoint") ?: 21
            setCoolingSetpoint(csp - 1)
            break
        default:
            logDebug "tempDown(): no setpoint to lower when mode=$m"
            break
    }
}

//─────────────────────────────────────────────────────────────────────────────
//   LOGGING
//─────────────────────────────────────────────────────────────────────────────

def logDebug(message) {
    if (settings.debugLogging) log.debug "DEBUG: ${message}"
}
def logInfo(message)  { log.info  "INFO:  ${message}" }
def logWarn(message)  { log.warn  "WARN:  ${message}" }
