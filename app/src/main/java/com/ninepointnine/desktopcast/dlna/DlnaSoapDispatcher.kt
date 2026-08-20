package com.ninepointnine.desktopcast.dlna

class DlnaSoapDispatcher(
    private val controller: DlnaPlaybackController,
) {
    fun dispatch(service: DlnaService, body: String): String {
        val request = DlnaXml.parseSoapAction(body)
        validateInstance(request)
        return when (service) {
            DlnaService.AV_TRANSPORT -> avTransport(request)
            DlnaService.RENDERING_CONTROL -> renderingControl(request)
            DlnaService.CONNECTION_MANAGER -> connectionManager(request)
        }
    }

    private fun avTransport(request: DlnaSoapAction): String {
        val snapshot = controller.snapshot()
        return when (request.name) {
            "SetAVTransportURI" -> {
                val uri = request.required("CurrentURI")
                if (uri.isBlank()) throw DlnaControlException(714, "Illegal MIME-type")
                controller.setMedia(DlnaXml.parseMedia(uri, request.arguments["CurrentURIMetaData"].orEmpty()))
                response(DlnaService.AV_TRANSPORT, request.name)
            }
            "SetNextAVTransportURI" -> response(DlnaService.AV_TRANSPORT, request.name)
            "Play" -> {
                if (snapshot.media == null) throw DlnaControlException(701, "Transition not available")
                controller.play()
                response(DlnaService.AV_TRANSPORT, request.name)
            }
            "Pause" -> {
                controller.pause()
                response(DlnaService.AV_TRANSPORT, request.name)
            }
            "Stop" -> {
                controller.stop()
                response(DlnaService.AV_TRANSPORT, request.name)
            }
            "Seek" -> {
                val unit = request.required("Unit")
                if (unit != "REL_TIME" && unit != "ABS_TIME") {
                    throw DlnaControlException(710, "Seek mode not supported")
                }
                controller.seekTo(DlnaXml.parseTime(request.required("Target")))
                response(DlnaService.AV_TRANSPORT, request.name)
            }
            "GetTransportInfo" -> response(
                DlnaService.AV_TRANSPORT,
                request.name,
                "<CurrentTransportState>${snapshot.transportState.wireValue}</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>",
            )
            "GetPositionInfo" -> response(
                DlnaService.AV_TRANSPORT,
                request.name,
                "<Track>${if (snapshot.media == null) 0 else 1}</Track>" +
                    "<TrackDuration>${DlnaXml.formatTime(snapshot.durationMs)}</TrackDuration>" +
                    "<TrackMetaData>${DlnaXml.escape(snapshot.media?.metadata.orEmpty())}</TrackMetaData>" +
                    "<TrackURI>${DlnaXml.escape(snapshot.media?.uri.orEmpty())}</TrackURI>" +
                    "<RelTime>${DlnaXml.formatTime(snapshot.positionMs)}</RelTime>" +
                    "<AbsTime>${DlnaXml.formatTime(snapshot.positionMs)}</AbsTime>" +
                    "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>",
            )
            "GetMediaInfo" -> response(
                DlnaService.AV_TRANSPORT,
                request.name,
                "<NrTracks>${if (snapshot.media == null) 0 else 1}</NrTracks>" +
                    "<MediaDuration>${DlnaXml.formatTime(snapshot.durationMs)}</MediaDuration>" +
                    "<CurrentURI>${DlnaXml.escape(snapshot.media?.uri.orEmpty())}</CurrentURI>" +
                    "<CurrentURIMetaData>${DlnaXml.escape(snapshot.media?.metadata.orEmpty())}</CurrentURIMetaData>" +
                    "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>" +
                    "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                    "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>",
            )
            "GetDeviceCapabilities" -> response(
                DlnaService.AV_TRANSPORT,
                request.name,
                "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>" +
                    "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>",
            )
            "GetTransportSettings" -> response(
                DlnaService.AV_TRANSPORT,
                request.name,
                "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>",
            )
            "GetCurrentTransportActions" -> response(
                DlnaService.AV_TRANSPORT,
                request.name,
                "<Actions>${currentActions(snapshot)}</Actions>",
            )
            else -> invalidAction(request.name)
        }
    }

    private fun renderingControl(request: DlnaSoapAction): String {
        validateMasterChannel(request)
        val snapshot = controller.snapshot()
        return when (request.name) {
            "ListPresets" -> response(
                DlnaService.RENDERING_CONTROL,
                request.name,
                "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>",
            )
            "SelectPreset" -> response(DlnaService.RENDERING_CONTROL, request.name)
            "GetVolume" -> response(
                DlnaService.RENDERING_CONTROL,
                request.name,
                "<CurrentVolume>${snapshot.volume}</CurrentVolume>",
            )
            "SetVolume" -> {
                val volume = request.required("DesiredVolume").toIntOrNull()
                    ?: throw DlnaControlException(402, "Invalid volume")
                controller.setVolume(volume.coerceIn(0, 100))
                response(DlnaService.RENDERING_CONTROL, request.name)
            }
            "GetMute" -> response(
                DlnaService.RENDERING_CONTROL,
                request.name,
                "<CurrentMute>${if (snapshot.muted) 1 else 0}</CurrentMute>",
            )
            "SetMute" -> {
                val value = request.required("DesiredMute")
                controller.setMuted(value == "1" || value.equals("true", ignoreCase = true))
                response(DlnaService.RENDERING_CONTROL, request.name)
            }
            "GetVolumeDB" -> response(
                DlnaService.RENDERING_CONTROL,
                request.name,
                "<CurrentVolume>${volumeDb(snapshot.volume)}</CurrentVolume>",
            )
            "GetVolumeDBRange" -> response(
                DlnaService.RENDERING_CONTROL,
                request.name,
                "<MinValue>-6000</MinValue><MaxValue>0</MaxValue>",
            )
            else -> invalidAction(request.name)
        }
    }

    private fun connectionManager(request: DlnaSoapAction): String = when (request.name) {
        "GetProtocolInfo" -> response(
            DlnaService.CONNECTION_MANAGER,
            request.name,
            "<Source></Source><Sink>${DlnaXml.escape(DlnaDescriptions.sinkProtocolInfo)}</Sink>",
        )
        "PrepareForConnection" -> response(
            DlnaService.CONNECTION_MANAGER,
            request.name,
            "<ConnectionID>0</ConnectionID><AVTransportID>0</AVTransportID><RcsID>0</RcsID>",
        )
        "ConnectionComplete" -> response(DlnaService.CONNECTION_MANAGER, request.name)
        "GetCurrentConnectionIDs" -> response(
            DlnaService.CONNECTION_MANAGER,
            request.name,
            "<ConnectionIDs>0</ConnectionIDs>",
        )
        "GetCurrentConnectionInfo" -> response(
            DlnaService.CONNECTION_MANAGER,
            request.name,
            "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>" +
                "<ProtocolInfo>${DlnaXml.escape(controller.snapshot().media?.mimeType.orEmpty())}</ProtocolInfo>" +
                "<PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID>" +
                "<Direction>Input</Direction><Status>OK</Status>",
        )
        else -> invalidAction(request.name)
    }

    private fun validateInstance(request: DlnaSoapAction) {
        val value = request.arguments["InstanceID"] ?: return
        if (value != "0") throw DlnaControlException(718, "Invalid instance")
    }

    private fun validateMasterChannel(request: DlnaSoapAction) {
        val channel = request.arguments["Channel"] ?: return
        if (!channel.equals("Master", ignoreCase = true)) {
            throw DlnaControlException(600, "Unsupported channel")
        }
    }

    private fun currentActions(snapshot: DlnaPlaybackSnapshot): String = when (snapshot.transportState) {
        DlnaTransportState.NO_MEDIA -> ""
        DlnaTransportState.STOPPED -> "Play,Stop"
        DlnaTransportState.TRANSITIONING -> "Stop"
        DlnaTransportState.PLAYING -> if (snapshot.durationMs > 0) "Pause,Stop,Seek" else "Pause,Stop"
        DlnaTransportState.PAUSED -> if (snapshot.durationMs > 0) "Play,Stop,Seek" else "Play,Stop"
    }

    private fun volumeDb(volume: Int): Int = if (volume <= 0) -6000 else -6000 + volume * 60

    private fun DlnaSoapAction.required(name: String): String = arguments[name]
        ?: throw DlnaControlException(402, "Missing $name")

    private fun response(service: DlnaService, action: String, arguments: String = "") =
        DlnaXml.envelope(service, action, arguments)

    private fun invalidAction(name: String): Nothing =
        throw DlnaControlException(401, "Invalid action: $name")
}
