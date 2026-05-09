(function () {
    const rows = Array.from(document.querySelectorAll(".recording-row"));
    const display = document.getElementById("display");
    const placeholder = document.getElementById("placeholder");
    const playPause = document.getElementById("playPause");
    const restart = document.getElementById("restart");
    const fit = document.getElementById("fit");
    const speed = document.getElementById("speed");
    const seek = document.getElementById("seek");
    const currentTime = document.getElementById("currentTime");
    const duration = document.getElementById("duration");
    const status = document.getElementById("status");
    const histogram = document.getElementById("histogram");
    const activitySummary = document.getElementById("activitySummary");
    const keySummary = document.getElementById("keySummary");
    const keyEvents = document.getElementById("keyEvents");

    let recording = null;
    let displayElement = null;
    let dragging = false;
    let analysisRequest = null;
    let playbackSpeed = 1;

    const playbackClock = (function () {
        const realDateNow = Date.now.bind(Date);
        const realSetTimeout = window.setTimeout.bind(window);
        const clock = {
            active: false,
            speed: 1,
            realAnchor: realDateNow(),
            virtualAnchor: realDateNow()
        };

        function virtualNow() {
            if (!clock.active) {
                return realDateNow();
            }
            return clock.virtualAnchor + ((realDateNow() - clock.realAnchor) * clock.speed);
        }

        Date.now = virtualNow;
        window.setTimeout = function (callback, delay, ...args) {
            const safeDelay = Number(delay) || 0;
            const adjustedDelay = clock.active && safeDelay > 0
                ? safeDelay / Math.max(clock.speed, 0.01)
                : safeDelay;
            return realSetTimeout(callback, adjustedDelay, ...args);
        };

        return {
            start(nextSpeed) {
                clock.virtualAnchor = virtualNow();
                clock.realAnchor = realDateNow();
                clock.speed = Math.max(Number(nextSpeed) || 1, 0.01);
                clock.active = true;
            },
            stop() {
                clock.virtualAnchor = virtualNow();
                clock.realAnchor = realDateNow();
                clock.active = false;
            },
            setSpeed(nextSpeed) {
                clock.virtualAnchor = virtualNow();
                clock.realAnchor = realDateNow();
                clock.speed = Math.max(Number(nextSpeed) || 1, 0.01);
            }
        };
    })();

    function recordingUrl(path) {
        return "/api/recordings/" + path.split("/").map(encodeURIComponent).join("/");
    }

    function analysisUrl(path) {
        return "/api/recording-analysis/" + path.split("/").map(encodeURIComponent).join("/");
    }

    function setStatus(message, error) {
        status.textContent = message;
        status.classList.toggle("error", Boolean(error));
    }

    function setEnabled(enabled) {
        playPause.disabled = !enabled;
        restart.disabled = !enabled;
        fit.disabled = !enabled;
        seek.disabled = !enabled;
        speed.disabled = !enabled;
    }

    function setPlayIcon(playing) {
        playPause.innerHTML = "";
        const icon = document.createElement("span");
        icon.className = playing ? "pause-icon" : "play-icon";
        playPause.appendChild(icon);
        playPause.setAttribute("aria-label", playing ? "Pause" : "Play");
    }

    function formatTime(milliseconds) {
        const totalSeconds = Math.floor((milliseconds || 0) / 1000);
        const hours = Math.floor(totalSeconds / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        const padded = value => String(value).padStart(2, "0");
        return hours > 0
            ? `${hours}:${padded(minutes)}:${padded(seconds)}`
            : `${padded(minutes)}:${padded(seconds)}`;
    }

    function updateTime(position, total) {
        const safePosition = Math.max(0, position || 0);
        const safeTotal = Math.max(safePosition, total || 0);
        currentTime.textContent = formatTime(safePosition);
        duration.textContent = formatTime(safeTotal);
        seek.max = String(safeTotal);
        if (!dragging) {
            seek.value = String(safePosition);
        }
    }

    function fitDisplay() {
        if (!recording || !displayElement) {
            return;
        }

        const guacDisplay = recording.getDisplay();
        const width = guacDisplay.getWidth();
        const height = guacDisplay.getHeight();
        const bounds = display.getBoundingClientRect();

        if (width <= 0 || height <= 0 || bounds.width <= 0 || bounds.height <= 0) {
            return;
        }

        const scale = Math.min(bounds.width / width, bounds.height / height);
        guacDisplay.scale(scale);
    }

    function clearRecording() {
        if (recording) {
            recording.abort();
            recording = null;
        }

        playbackClock.stop();

        if (analysisRequest) {
            analysisRequest.abort();
            analysisRequest = null;
        }

        display.replaceChildren();
        displayElement = null;
        setEnabled(false);
        setPlayIcon(false);
        updateTime(0, 0);
        renderHistogram(null);
        renderKeyEvents(null);
    }

    function renderHistogram(analysis) {
        histogram.replaceChildren();

        if (!analysis || !analysis.buckets || analysis.buckets.length === 0) {
            activitySummary.textContent = "No activity";
            const empty = document.createElement("div");
            empty.className = "muted-row";
            empty.textContent = "Activity will appear after analysis finishes.";
            histogram.appendChild(empty);
            return;
        }

        const maxInstructions = Math.max(1, ...analysis.buckets.map(bucket => bucket.instructions));
        const maxKeys = Math.max(1, ...analysis.buckets.map(bucket => bucket.keys));
        const activeBuckets = analysis.buckets.filter(bucket => bucket.instructions > 0).length;
        activitySummary.textContent = `${activeBuckets}/${analysis.buckets.length} active segments`;

        analysis.buckets.forEach(bucket => {
            const bar = document.createElement("button");
            bar.type = "button";
            bar.className = "histogram-bar";
            bar.style.height = `${Math.max(2, (bucket.instructions / maxInstructions) * 100)}%`;
            bar.style.setProperty("--key-height", `${(bucket.keys / maxKeys) * 100}%`);
            bar.title = `${formatTime(bucket.start)} - ${formatTime(bucket.end)}: ${bucket.instructions} instructions, ${bucket.keys} key events`;

            if (bucket.keys > 0) {
                bar.classList.add("has-keys");
            }

            bar.addEventListener("click", function () {
                if (recording) {
                    recording.seek(bucket.start);
                }
            });

            histogram.appendChild(bar);
        });
    }

    function displayKeyName(event) {
        const definition = event.definition || {};
        const value = definition.value;
        if (value === "\n") {
            return "Return";
        }
        if (value === "\t") {
            return "Tab";
        }
        if (value && value.trim() !== "") {
            return value;
        }
        return definition.name || `0x${Number(definition.keysym || 0).toString(16)}`;
    }

    function isPrintableKey(event) {
        const value = event.definition && event.definition.value;
        return event.pressed && typeof value === "string" && value.length === 1 && value !== "\n" && value !== "\t";
    }

    function isWordCharacter(value) {
        return /^[A-Za-z0-9_-]$/.test(value);
    }

    function pushTextGroup(groups, text, start, end) {
        if (text) {
            groups.push({
                type: "text",
                label: text,
                start,
                end
            });
        }
    }

    function groupKeyEvents(events) {
        const groups = [];
        let text = "";
        let textStart = 0;
        let textEnd = 0;

        events.forEach(event => {
            if (!event.pressed) {
                return;
            }

            if (isPrintableKey(event)) {
                const value = event.definition.value;

                if (isWordCharacter(value)) {
                    if (!text) {
                        textStart = event.timestamp || 0;
                    }
                    text += value;
                    textEnd = event.timestamp || textStart;
                    return;
                }

                pushTextGroup(groups, text, textStart, textEnd);
                text = "";
                groups.push({
                    type: "text",
                    label: value === " " ? "Space" : value,
                    start: event.timestamp || 0,
                    end: event.timestamp || 0
                });
                return;
            }

            pushTextGroup(groups, text, textStart, textEnd);
            text = "";
            groups.push({
                type: "key",
                label: displayKeyName(event),
                start: event.timestamp || 0,
                end: event.timestamp || 0
            });
        });

        pushTextGroup(groups, text, textStart, textEnd);
        return groups;
    }

    function renderKeyEvents(events) {
        keyEvents.replaceChildren();

        if (!events) {
            keySummary.textContent = "Waiting for parse";
            const row = document.createElement("div");
            row.className = "muted-row";
            row.textContent = "Key events will appear after the recording is parsed.";
            keyEvents.appendChild(row);
            return;
        }

        const groups = groupKeyEvents(events);
        keySummary.textContent = `${groups.length} groups from ${events.length} events`;

        if (groups.length === 0) {
            const row = document.createElement("div");
            row.className = "muted-row";
            row.textContent = "No key events were recorded for this session.";
            keyEvents.appendChild(row);
            return;
        }

        groups.forEach(group => {
            const row = document.createElement("button");
            row.type = "button";
            row.className = `key-row ${group.type === "text" ? "key-row-text" : "key-row-command"}`;
            row.title = "Seek to this keystroke group";
            row.addEventListener("click", function () {
                if (recording) {
                    recording.seek(Math.max(0, group.start || 0));
                }
            });

            const time = document.createElement("span");
            time.className = "key-time";
            time.textContent = group.start === group.end
                ? formatTime(group.start || 0)
                : `${formatTime(group.start || 0)}-${formatTime(group.end || 0)}`;

            const name = document.createElement("span");
            name.className = "key-name";
            const keyValue = document.createElement("span");
            keyValue.className = "key-value";
            keyValue.textContent = group.label;
            name.appendChild(keyValue);

            const state = document.createElement("span");
            state.className = "key-state";
            state.textContent = group.type === "text" ? "typed" : "key";

            row.append(time, name, state);
            keyEvents.appendChild(row);
        });
    }

    async function loadAnalysis(path) {
        analysisRequest = new AbortController();

        try {
            const response = await fetch(analysisUrl(path), { signal: analysisRequest.signal });
            if (!response.ok) {
                throw new Error(`Analysis failed (${response.status})`);
            }
            renderHistogram(await response.json());
        }
        catch (error) {
            if (error.name !== "AbortError") {
                activitySummary.textContent = "Analysis unavailable";
                histogram.replaceChildren();
                const row = document.createElement("div");
                row.className = "muted-row";
                row.textContent = error.message;
                histogram.appendChild(row);
            }
        }
        finally {
            analysisRequest = null;
        }
    }

    function loadRecording(row) {
        const path = row.dataset.path;
        const name = row.dataset.name || path;

        clearRecording();
        rows.forEach(item => item.classList.toggle("active", item === row));
        placeholder.classList.add("hidden");
        setStatus(`Loading ${name}...`, false);
        loadAnalysis(path);

        const tunnel = new Guacamole.StaticHTTPTunnel(recordingUrl(path));
        recording = new Guacamole.SessionRecording(tunnel, 250);
        displayElement = recording.getDisplay().getElement();
        display.appendChild(displayElement);

        recording.onprogress = function (loadedDuration) {
            updateTime(recording.getPosition(), loadedDuration);
            setStatus(`Loading ${name}...`, false);
            fitDisplay();
        };

        recording.onload = function () {
            updateTime(recording.getPosition(), recording.getDuration());
            setEnabled(true);
            fitDisplay();
            setStatus(name, false);
        };

        recording.onkeyevents = function (events) {
            renderKeyEvents(events);
        };

        recording.onplay = function () {
            playbackClock.start(playbackSpeed);
            setPlayIcon(true);
            setEnabled(true);
        };

        recording.onpause = function () {
            setPlayIcon(false);
            updateTime(recording.getPosition(), recording.getDuration());
            playbackClock.stop();
        };

        recording.onseek = function (position) {
            updateTime(position, recording.getDuration());
            fitDisplay();
        };

        recording.onerror = function (message) {
            setPlayIcon(false);
            setStatus(message || "Unable to play recording.", true);
        };

        tunnel.onerror = function (statusCode) {
            setStatus(`Unable to load recording (${statusCode.code || "error"}).`, true);
        };

        recording.connect();
    }

    playPause.addEventListener("click", function () {
        if (!recording) {
            return;
        }

        if (recording.isPlaying()) {
            recording.pause();
        }
        else {
            playbackClock.start(playbackSpeed);
            recording.play();
        }
    });

    speed.addEventListener("change", function () {
        playbackSpeed = Number(speed.value) || 1;

        if (!recording) {
            return;
        }

        if (recording.isPlaying()) {
            recording.pause();
            playbackClock.setSpeed(playbackSpeed);
            recording.play();
        }
        else {
            playbackClock.setSpeed(playbackSpeed);
        }
    });

    restart.addEventListener("click", function () {
        if (recording) {
            recording.seek(0);
        }
    });

    fit.addEventListener("click", fitDisplay);
    window.addEventListener("resize", fitDisplay);

    seek.addEventListener("input", function () {
        dragging = true;
        currentTime.textContent = formatTime(Number(seek.value));
    });

    seek.addEventListener("change", function () {
        dragging = false;
        if (recording) {
            recording.seek(Number(seek.value));
        }
    });

    rows.forEach(row => row.addEventListener("click", () => loadRecording(row)));

    setPlayIcon(false);

    if (rows.length > 0) {
        loadRecording(document.querySelector(".recording-row.active") || rows[0]);
    }
})();
