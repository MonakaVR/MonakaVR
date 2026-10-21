package dev.monaka.tracking.pico.desktop;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * Reference-only interface for the legacy native PICO receiver.
 *
 * Method names are exact exported C symbol names: JNA resolves them without a
 * FunctionMapper. Keep the public package, method signatures and native names.
 *
 * This archived receiver is excluded from the current Desktop build.
 */
public interface PicoMotionTrackerBridgeNativeLibrary extends Library {
	int pico_ot_bridge_c_api_version();
	int pico_ot_bridge_receiver_config_size();
	int pico_ot_bridge_tracker_state_size();
	int pico_ot_bridge_clock_estimate_size();

	void pico_ot_bridge_receiver_default_config(NativeReceiverConfig config);
	Pointer pico_ot_bridge_receiver_create();
	void pico_ot_bridge_receiver_destroy(Pointer receiver);
	int pico_ot_bridge_receiver_start(Pointer receiver, NativeReceiverConfig config);
	void pico_ot_bridge_receiver_stop(Pointer receiver);
	int pico_ot_bridge_receiver_pump(Pointer receiver);
	byte pico_ot_bridge_receiver_is_running(Pointer receiver);
	short pico_ot_bridge_receiver_listen_port(Pointer receiver);
	long pico_ot_bridge_receiver_active_sender_session_id(Pointer receiver);
	int pico_ot_bridge_receiver_tracker_count(Pointer receiver);
	int pico_ot_bridge_receiver_get_tracker(Pointer receiver, int index, NativeTrackerState out);
	byte pico_ot_bridge_receiver_get_clock_estimate(Pointer receiver, NativeClockEstimate out);
	String pico_ot_bridge_receiver_last_error(Pointer receiver);
	String pico_ot_bridge_status_string(int status);
}
