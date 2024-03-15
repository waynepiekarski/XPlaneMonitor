package net.waynepiekarski.xplanemonitor.xplane

object DataRef {
    const val APP = "APP"
    const val MAP = "MAP"
    const val NA = "N/A"
    const val PLN = "PLN"
    const val VOR = "VOR"

    // Seatbelt Sign
    const val SB_AUTO = "SB-Auto"
    const val SB_NA = "n/a"
    const val SB_OFF = "SB-Off"
    const val SB_ON = "SB-On"

    object Aircraft {
        const val ACF_TAILNUM = "sim/aircraft/view/acf_tailnum"
    }

    object Cockpit {
        object Controls {
            const val FLAP_HANDLE_DEPLOY_RATIO = "sim/cockpit2/controls/flap_handle_deploy_ratio"
            const val LEFT_BRAKE_RATIO = "sim/cockpit2/controls/left_brake_ratio"
            const val PARKING_BRAKE_RATIO = "sim/cockpit2/controls/parking_brake_ratio"
            const val RIGHT_BRAKE_RATIO = "sim/cockpit2/controls/right_brake_ratio"
            const val SPEEDBRAKE_RATIO = "sim/cockpit2/controls/speedbrake_ratio"
        }

        object Gauges {
            const val ALTITUDE_FT_PILOT = "sim/cockpit2/gauges/indicators/altitude_ft_pilot"
            const val COMPASS_HEADING_DEG_MAG = "sim/cockpit2/gauges/indicators/compass_heading_deg_mag"
        }

        object Radios {
            const val NAV1_DME_DIST_M = "sim/cockpit/radios/nav1_dme_dist_m"
            const val NAV2_DME_DIST_M = "sim/cockpit/radios/nav2_dme_dist_m"
        }

        object Switches {
            const val EFIS_MAPSUBMODE = "sim/cockpit/switches/EFIS_map_submode" // FF767
            const val EFIS_MAP_RANGE_SELECTOR =
                "sim/cockpit/switches/EFIS_map_range_selector" // XP737
            const val EFIS_SHOWS_AIRPORTS = "sim/cockpit/switches/EFIS_shows_airports" // ARPT XP737
            const val EFIS_SHOWS_CTR_TODO = "sim/cockpit/switches/EFIS_shows_ctr_TODO"
            const val EFIS_SHOWS_DATA = "sim/cockpit/switches/EFIS_shows_data"
            const val EFIS_SHOWS_TCAS = "sim/cockpit/switches/EFIS_shows_tcas" // TFC XP737
            const val EFIS_SHOWS_TERRAIN = "sim/cockpit/switches/EFIS_shows_terrain"
            const val EFIS_SHOWS_VORS = "sim/cockpit/switches/EFIS_shows_VORs" // STA XP737
            const val EFIS_SHOWS_WAYPOINTS =
                "sim/cockpit/switches/EFIS_shows_waypoints" // WPT XP737
            const val EFIS_SHOWS_WEATHER = "sim/cockpit/switches/EFIS_shows_weather" // WXR XP737

            const val GENERIC_LIGHTS_SWITCH = "sim/cockpit2/switches/generic_lights_switch"
            const val LANDING_LIGHTS_SWITCH = "sim/cockpit2/switches/landing_lights_switch"
        }

        object Pressurization {
            const val MAX_ALLOWABLE_ALTITUDE_FT =
                "sim/cockpit2/pressurization/actuators/max_allowable_altitude_ft"
        }

        object Warnings {
            object Annunciators {
                const val REVERSE = "sim/cockpit/warnings/annunciators/reverse"
            }
        }
    }

    object FlightModel {
        object Controls {
            const val FLAPREQUEST = "sim/flightmodel/controls/flaprqst"
        }

        object Forces {
            const val G_NRML = "sim/flightmodel/forces/g_nrml"
        }

        object Gear {
            const val TIRE_VERTICAL_FORCE_N_MTR = "sim/flightmodel2/gear/tire_vertical_force_n_mtr"
        }

        object Position {
            const val ELEVATION = "sim/flightmodel/position/elevation"
            const val INDICATED_AIRSPEED = "sim/flightmodel/position/indicated_airspeed"
            const val LATITUDE = "sim/flightmodel/position/latitude"
            const val LONGITUDE = "sim/flightmodel/position/longitude"
            const val VH_IND_FPM = "sim/flightmodel/position/vh_ind_fpm"
            const val Y_AGL = "sim/flightmodel/position/y_agl"
        }
    }

    const val NDPANEL_HSIMODEBUTTON = "1-sim/ndpanel/1/hsiModeButton"
    const val NDPANEL_HSIMODEROTARY = "1-sim/ndpanel/1/hsiModeRotary" // XP737 (this is really for FF767?)
    const val NDPANEL_HSIRANGEBUTTON = "1-sim/ndpanel/1/hsiRangeButton"
    const val NDPANEL_HSIRANGEROTARY = "1-sim/ndpanel/1/hsiRangeRotary" // FF767
    const val NDPANEL_HSITERR = "1-sim/ndpanel/1/hsiTerr"
    const val NDPANEL_HSIWXR = "1-sim/ndpanel/1/hsiWxr"
    const val NDPANEL_MAP2 = "1-sim/ndpanel/1/map2"
    const val NDPANEL_MAP3 = "1-sim/ndpanel/1/map3"
    const val NDPANEL_MAP4 = "1-sim/ndpanel/1/map4"
    const val NDPANEL_MAP5 = "1-sim/ndpanel/1/map5"

    object Operation {
        const val PUSHBUTTON_PAUSE_TOGGLE = "sim/operation/pause_toggle"
    }

    object Time {
        const val TIME_PAUSED = "sim/time/paused"
    }

    object B738 {
        object EFIS {
            object Control {
                const val ARPT_PRESS = "laminar/B738/EFIS_control/capt/push_button/arpt_press"
                const val CTR_PRESS = "laminar/B738/EFIS_control/capt/push_button/ctr_press"
                const val DATA_PRESS = "laminar/B738/EFIS_control/capt/push_button/data_press"
                const val MAP_RANGE_DOWN = "laminar/B738/EFIS_control/capt/map_range_dn" // ZB737
                const val MAP_RANGE_UP = "laminar/B738/EFIS_control/capt/map_range_up" // ZB737
                const val POS_PRESS = "laminar/B738/EFIS_control/capt/push_button/pos_press"
                const val STA_PRESS = "laminar/B738/EFIS_control/capt/push_button/sta_press"
                const val TERR_PRESS = "laminar/B738/EFIS_control/capt/push_button/terr_press"
                const val TFC_PRESS = "laminar/B738/EFIS_control/capt/push_button/tfc_press"
                const val WPT_PRESS = "laminar/B738/EFIS_control/capt/push_button/wpt_press"
                const val WXR_PRESS = "laminar/B738/EFIS_control/capt/push_button/wxr_press"
            }

            const val AIRPORT_ON = "laminar/B738/EFIS/EFIS_airport_on" // ARPT ZB737
            const val CAPTAIN_MAP_RANGE = "laminar/B738/EFIS/capt/map_range" // Range ZB737
            const val CONTROL_CAPTAIN_MAPMODEPOS = "laminar/B738/EFIS_control/capt/map_mode_pos" // ZB737
            const val FIX_ON = "laminar/B738/EFIS/EFIS_fix_on" // WPT ZB737
            const val VOR_ON = "laminar/B738/EFIS/EFIS_vor_on" // STA ZB737
            const val WX_ON = "laminar/B738/EFIS/EFIS_wx_on" // WXR ZB737
        }

        object Ice {
            const val WINDOW_HEAT_L_FWD_POS = "laminar/B738/ice/window_heat_l_fwd_pos"
            const val WINDOW_HEAT_R_FWD_POS = "laminar/B738/ice/window_heat_r_fwd_pos"
            const val WINDOW_HEAD_L_SIDE_POS = "laminar/B738/ice/window_heat_l_side_pos"
            const val WINDOW_HEAD_R_SIDE_POS = "laminar/B738/ice/window_heat_r_side_pos"
        }

        object PushButton {
            const val ATTEND = "laminar/B738/push_button/attend"
        }

        object ToggleSwitch {
            const val CAPT_PROBES_POS = "laminar/B738/toggle_switch/capt_probes_pos"
            const val FO_PROBES_POS = "laminar/B738/toggle_switch/fo_probes_pos"
            const val SEATBELT_SIGN_DN = "laminar/B738/toggle_switch/seatbelt_sign_dn"
            const val SEATBELT_SIGN_POS = "laminar/B738/toggle_switch/seatbelt_sign_pos"
            const val SEATBELT_SIGN_UP = "laminar/B738/toggle_switch/seatbelt_sign_up"
            const val WINDOW_HEAT_LEFT_FORWARD = "laminar/B738/toggle_switch/window_heat_l_fwd"
            const val WINDOW_HEAT_RIGHT_FORWARD = "laminar/B738/toggle_switch/window_heat_r_fwd"
            const val WINDOW_HEAT_LEFT_SIDE = "laminar/B738/toggle_switch/window_heat_l_side"
            const val WINDOW_HEAT_RIGHT_SIDE = "laminar/B738/toggle_switch/window_heat_r_side"
        }
    }
}
