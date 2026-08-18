package com.tcrrry.desktopcast.dlna

object DlnaDescriptions {
    const val HTTP_PORT = 8200

    val sinkProtocolInfo: String = listOf(
        "video/mp4",
        "video/mpeg",
        "video/MP2T",
        "video/x-matroska",
        "video/webm",
        "video/quicktime",
        "video/3gpp",
        "video/x-msvideo",
        "application/vnd.apple.mpegurl",
        "application/x-mpegURL",
        "application/dash+xml",
        "audio/mpeg",
        "audio/mp4",
        "audio/aac",
        "audio/flac",
        "audio/x-flac",
        "audio/wav",
        "audio/x-wav",
        "audio/ogg",
        "audio/L16",
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/bmp",
    ).joinToString(",") { "http-get:*:$it:*" } + ",http-get:*:*:*"

    fun device(uuid: String, friendlyName: String, baseUrl: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>${DlnaXml.escape(baseUrl)}</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>${DlnaXml.escape(friendlyName)}</friendlyName>
    <manufacturer>TCRRRY</manufacturer>
    <modelDescription>Android 9 DLNA and AirPlay receiver</modelDescription>
    <modelName>03投屏</modelName>
    <modelNumber>1</modelNumber>
    <serialNumber>${DlnaXml.escape(uuid.takeLast(12))}</serialNumber>
    <UDN>uuid:${DlnaXml.escape(uuid)}</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      ${serviceDescription(DlnaService.AV_TRANSPORT)}
      ${serviceDescription(DlnaService.RENDERING_CONTROL)}
      ${serviceDescription(DlnaService.CONNECTION_MANAGER)}
    </serviceList>
    <presentationURL>/</presentationURL>
  </device>
</root>"""

    fun scpd(service: DlnaService): String = when (service) {
        DlnaService.AV_TRANSPORT -> AV_TRANSPORT_SCPD
        DlnaService.RENDERING_CONTROL -> RENDERING_CONTROL_SCPD
        DlnaService.CONNECTION_MANAGER -> CONNECTION_MANAGER_SCPD
    }

    private fun serviceDescription(service: DlnaService): String =
        """<service>
        <serviceType>${service.serviceType}</serviceType>
        <serviceId>urn:upnp-org:serviceId:${service.pathName}</serviceId>
        <SCPDURL>/scpd/${service.pathName}.xml</SCPDURL>
        <controlURL>/control/${service.pathName}</controlURL>
        <eventSubURL>/event/${service.pathName}</eventSubURL>
      </service>"""

    private val AV_TRANSPORT_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("SetAVTransportURI", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("CurrentURI", "AVTransportURI") + input("CurrentURIMetaData", "AVTransportURIMetaData"))}
    ${action("SetNextAVTransportURI", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("NextURI", "AVTransportURI") + input("NextURIMetaData", "AVTransportURIMetaData"))}
    ${action("GetMediaInfo", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("NrTracks", "NumberOfTracks") + output("MediaDuration", "CurrentMediaDuration") + output("CurrentURI", "AVTransportURI") + output("CurrentURIMetaData", "AVTransportURIMetaData") + output("NextURI", "AVTransportURI") + output("NextURIMetaData", "AVTransportURIMetaData") + output("PlayMedium", "PlaybackStorageMedium") + output("RecordMedium", "RecordStorageMedium") + output("WriteStatus", "RecordMediumWriteStatus"))}
    ${action("GetTransportInfo", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("CurrentTransportState", "TransportState") + output("CurrentTransportStatus", "TransportStatus") + output("CurrentSpeed", "TransportPlaySpeed"))}
    ${action("GetPositionInfo", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("Track", "CurrentTrack") + output("TrackDuration", "CurrentTrackDuration") + output("TrackMetaData", "CurrentTrackMetaData") + output("TrackURI", "CurrentTrackURI") + output("RelTime", "RelativeTimePosition") + output("AbsTime", "AbsoluteTimePosition") + output("RelCount", "RelativeCounterPosition") + output("AbsCount", "AbsoluteCounterPosition"))}
    ${action("GetDeviceCapabilities", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("PlayMedia", "PossiblePlaybackStorageMedia") + output("RecMedia", "PossibleRecordStorageMedia") + output("RecQualityModes", "PossibleRecordQualityModes"))}
    ${action("GetTransportSettings", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("PlayMode", "CurrentPlayMode") + output("RecQualityMode", "CurrentRecordQualityMode"))}
    ${action("Stop", input("InstanceID", "A_ARG_TYPE_InstanceID"))}
    ${action("Play", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Speed", "TransportPlaySpeed"))}
    ${action("Pause", input("InstanceID", "A_ARG_TYPE_InstanceID"))}
    ${action("Seek", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Unit", "A_ARG_TYPE_SeekMode") + input("Target", "A_ARG_TYPE_SeekTarget"))}
    ${action("GetCurrentTransportActions", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("Actions", "CurrentTransportActions"))}
  </actionList>
  <serviceStateTable>
    ${variable("LastChange", "string", true)}
    ${variable("A_ARG_TYPE_InstanceID", "ui4")}
    ${variable("AVTransportURI", "string")}
    ${variable("AVTransportURIMetaData", "string")}
    ${variable("NumberOfTracks", "ui4")}
    ${variable("CurrentMediaDuration", "string")}
    ${variable("PlaybackStorageMedium", "string")}
    ${variable("RecordStorageMedium", "string")}
    ${variable("RecordMediumWriteStatus", "string")}
    ${variable("TransportState", "string")}
    ${variable("TransportStatus", "string")}
    ${variable("TransportPlaySpeed", "string")}
    ${variable("CurrentTrack", "ui4")}
    ${variable("CurrentTrackDuration", "string")}
    ${variable("CurrentTrackMetaData", "string")}
    ${variable("CurrentTrackURI", "string")}
    ${variable("RelativeTimePosition", "string")}
    ${variable("AbsoluteTimePosition", "string")}
    ${variable("RelativeCounterPosition", "i4")}
    ${variable("AbsoluteCounterPosition", "i4")}
    ${variable("PossiblePlaybackStorageMedia", "string")}
    ${variable("PossibleRecordStorageMedia", "string")}
    ${variable("PossibleRecordQualityModes", "string")}
    ${variable("CurrentPlayMode", "string")}
    ${variable("CurrentRecordQualityMode", "string")}
    ${variable("A_ARG_TYPE_SeekMode", "string")}
    ${variable("A_ARG_TYPE_SeekTarget", "string")}
    ${variable("CurrentTransportActions", "string")}
  </serviceStateTable>
</scpd>"""

    private val RENDERING_CONTROL_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("ListPresets", input("InstanceID", "A_ARG_TYPE_InstanceID") + output("CurrentPresetNameList", "PresetNameList"))}
    ${action("SelectPreset", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("PresetName", "PresetName"))}
    ${action("GetMute", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Channel", "A_ARG_TYPE_Channel") + output("CurrentMute", "Mute"))}
    ${action("SetMute", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Channel", "A_ARG_TYPE_Channel") + input("DesiredMute", "Mute"))}
    ${action("GetVolume", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Channel", "A_ARG_TYPE_Channel") + output("CurrentVolume", "Volume"))}
    ${action("SetVolume", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Channel", "A_ARG_TYPE_Channel") + input("DesiredVolume", "Volume"))}
    ${action("GetVolumeDB", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Channel", "A_ARG_TYPE_Channel") + output("CurrentVolume", "VolumeDB"))}
    ${action("GetVolumeDBRange", input("InstanceID", "A_ARG_TYPE_InstanceID") + input("Channel", "A_ARG_TYPE_Channel") + output("MinValue", "VolumeDB") + output("MaxValue", "VolumeDB"))}
  </actionList>
  <serviceStateTable>
    ${variable("LastChange", "string", true)}
    ${variable("A_ARG_TYPE_InstanceID", "ui4")}
    ${variable("A_ARG_TYPE_Channel", "string")}
    ${variable("PresetNameList", "string")}
    ${variable("PresetName", "string")}
    ${variable("Mute", "boolean")}
    ${variable("Volume", "ui2")}
    ${variable("VolumeDB", "i2")}
  </serviceStateTable>
</scpd>"""

    private val CONNECTION_MANAGER_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("GetProtocolInfo", output("Source", "SourceProtocolInfo") + output("Sink", "SinkProtocolInfo"))}
    ${action("PrepareForConnection", input("RemoteProtocolInfo", "A_ARG_TYPE_ProtocolInfo") + input("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager") + input("PeerConnectionID", "A_ARG_TYPE_ConnectionID") + input("Direction", "A_ARG_TYPE_Direction") + output("ConnectionID", "A_ARG_TYPE_ConnectionID") + output("AVTransportID", "A_ARG_TYPE_AVTransportID") + output("RcsID", "A_ARG_TYPE_RcsID"))}
    ${action("ConnectionComplete", input("ConnectionID", "A_ARG_TYPE_ConnectionID"))}
    ${action("GetCurrentConnectionIDs", output("ConnectionIDs", "CurrentConnectionIDs"))}
    ${action("GetCurrentConnectionInfo", input("ConnectionID", "A_ARG_TYPE_ConnectionID") + output("RcsID", "A_ARG_TYPE_RcsID") + output("AVTransportID", "A_ARG_TYPE_AVTransportID") + output("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo") + output("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager") + output("PeerConnectionID", "A_ARG_TYPE_ConnectionID") + output("Direction", "A_ARG_TYPE_Direction") + output("Status", "A_ARG_TYPE_ConnectionStatus"))}
  </actionList>
  <serviceStateTable>
    ${variable("SourceProtocolInfo", "string", true)}
    ${variable("SinkProtocolInfo", "string", true)}
    ${variable("CurrentConnectionIDs", "string", true)}
    ${variable("A_ARG_TYPE_ConnectionStatus", "string")}
    ${variable("A_ARG_TYPE_ConnectionManager", "string")}
    ${variable("A_ARG_TYPE_Direction", "string")}
    ${variable("A_ARG_TYPE_ProtocolInfo", "string")}
    ${variable("A_ARG_TYPE_ConnectionID", "i4")}
    ${variable("A_ARG_TYPE_AVTransportID", "i4")}
    ${variable("A_ARG_TYPE_RcsID", "i4")}
  </serviceStateTable>
</scpd>"""

    private fun action(name: String, arguments: String): String =
        "<action><name>$name</name>${if (arguments.isBlank()) "" else "<argumentList>$arguments</argumentList>"}</action>"

    private fun input(name: String, related: String): String = argument(name, "in", related)
    private fun output(name: String, related: String): String = argument(name, "out", related)

    private fun argument(name: String, direction: String, related: String): String =
        "<argument><name>$name</name><direction>$direction</direction><relatedStateVariable>$related</relatedStateVariable></argument>"

    private fun variable(name: String, type: String, evented: Boolean = false): String =
        "<stateVariable sendEvents=\"${if (evented) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType></stateVariable>"
}
