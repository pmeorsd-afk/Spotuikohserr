import asyncio
import json
import urllib.request
import websockets

async def test_presence():
    print("=== STARTING PRESENCE & IDEMPOTENCY E2E TEST ===")
    
    dashboard_uri = "ws://127.0.0.1:8787/ws/dashboard"
    app_uri = "ws://127.0.0.1:8787/ws/presence"
    
    async with websockets.connect(dashboard_uri) as dash_ws:
        print("[Dashboard] Connected to /ws/dashboard")
        
        # 1. Should receive initial snapshot
        init_snap_raw = await asyncio.wait_for(dash_ws.recv(), timeout=5.0)
        init_snap = json.loads(init_snap_raw)
        print("[Dashboard] Received initial snapshot:", init_snap.get("type"))
        assert init_snap.get("type") == "snapshot", "Initial message must be snapshot"
        
        # 2. Connect App Client
        async with websockets.connect(app_uri) as app_ws:
            print("[App] Connected to /ws/presence")
            import uuid
            test_run_id = uuid.uuid4().hex[:8]
            session_id = f"sess_test_{test_run_id}"
            user_id = f"usr_tester_{test_run_id}"
            playback_instance = f"play_uuid_{test_run_id}"
            global test_track_key
            test_track_key = f"track:test_{test_run_id}"
            
            hello_msg = {
                "type": "hello",
                "sessionId": session_id,
                "userId": user_id,
                "appVersion": "3.1-test",
                "state": "ACTIVE_IN_APP_IDLE",
                "playbackInstanceId": playback_instance,
                "positionMs": 0
            }
            await app_ws.send(json.dumps(hello_msg))
            print("[App] Sent hello")
            
            # Dashboard should receive user_joined delta
            dash_delta1_raw = await asyncio.wait_for(dash_ws.recv(), timeout=5.0)
            dash_delta1 = json.loads(dash_delta1_raw)
            print("[Dashboard] Received delta 1:", dash_delta1)
            assert dash_delta1.get("event") == "user_joined", f"Expected user_joined, got {dash_delta1}"
            assert dash_delta1.get("sessionId") == session_id
            
            # Send state_change (Playing Track) with playbackInstanceId
            state_msg = {
                "type": "state_change",
                "sessionId": session_id,
                "state": "ACTIVE_IN_APP_PLAYING",
                "isPlaying": True,
                "playbackInstanceId": playback_instance,
                "positionMs": 15000,
                "track": {
                    "contentKey": test_track_key,
                    "title": f"שיר המבחן {test_run_id}",
                    "artist": "אמן המבחן",
                    "mediaType": "track",
                    "isPodcast": False
                }
            }
            await app_ws.send(json.dumps(state_msg))
            print("[App] Sent state_change (Playing)")
            
            # Dashboard should receive state_changed delta
            dash_delta2_raw = await asyncio.wait_for(dash_ws.recv(), timeout=5.0)
            dash_delta2 = json.loads(dash_delta2_raw)
            print("[Dashboard] Received delta 2:", dash_delta2)
            assert dash_delta2.get("event") == "state_changed"
            assert dash_delta2.get("newState") == "ACTIVE_IN_APP_PLAYING"
            assert dash_delta2.get("trackTitle") == f"שיר המבחן {test_run_id}"
            
            # Send heartbeat
            hb_msg = {
                "type": "heartbeat",
                "sessionId": session_id,
                "state": "ACTIVE_IN_APP_PLAYING",
                "positionMs": 35000
            }
            await app_ws.send(json.dumps(hb_msg))
            print("[App] Sent heartbeat")
            await asyncio.sleep(0.5)
            
            # 3. Test reconnect on NEW socket with SAME playbackInstanceId (Idempotency test)
            print("[App] Simulating network switch / reconnect with SAME playbackInstanceId...")
            async with websockets.connect(app_uri) as app_ws_reconnect:
                await app_ws_reconnect.send(json.dumps(hello_msg))
                print("[App 2] Sent hello with same sessionId")
                
                # Check that dashboard received update
                dash_delta3_raw = await asyncio.wait_for(dash_ws.recv(), timeout=5.0)
                dash_delta3 = json.loads(dash_delta3_raw)
                print("[Dashboard] Received delta 3 (reconnect):", dash_delta3)
                
                # Send same track state_change again with same playbackInstanceId
                await app_ws_reconnect.send(json.dumps(state_msg))
                print("[App 2] Sent state_change with SAME playbackInstanceId")
                dash_delta_state_raw = await asyncio.wait_for(dash_ws.recv(), timeout=5.0)
                dash_delta_state = json.loads(dash_delta_state_raw)
                print("[Dashboard] Received delta state_change on reconnect:", dash_delta_state)
                assert dash_delta_state.get("event") == "state_changed"

                # Wait briefly
                await asyncio.sleep(0.5)

                # Send bye
                await app_ws_reconnect.send(json.dumps({
                    "type": "bye",
                    "sessionId": session_id,
                    "reason": "user_exit"
                }))
                print("[App 2] Sent bye")

                # Dashboard should receive user_left
                dash_delta4_raw = await asyncio.wait_for(dash_ws.recv(), timeout=5.0)
                dash_delta4 = json.loads(dash_delta4_raw)
                print("[Dashboard] Received delta 4 (user_left):", dash_delta4)
                assert dash_delta4.get("event") == "user_left"
                assert dash_delta4.get("sessionId") == session_id

    # 4. Verify SQLite Idempotency: count must be exactly 1, not 2
    req = urllib.request.urlopen("http://127.0.0.1:8787/api/analytics")
    data = json.loads(req.read().decode("utf-8"))
    top_tracks = data.get("topTracks", [])
    print("[Analytics] Top tracks response:", top_tracks)
    target_track = next((t for t in top_tracks if t.get("contentKey") == test_track_key), None)
    assert target_track is not None, f"Track {test_track_key} should be in topTracks"
    print(f"[Analytics] Track found: count={target_track.get('count')}")
    assert target_track.get("count") == 1, f"Expected count=1, got {target_track.get('count')}"
    print("[Analytics] IDEMPOTENCY VERIFIED: count is strictly 1 after multiple reconnects and state_changes!")

    print("=== ALL PRESENCE & IDEMPOTENCY TESTS PASSED 100%! ===")

if __name__ == "__main__":
    asyncio.run(test_presence())
