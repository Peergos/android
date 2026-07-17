(function() {
    if (window._androidWebAuthnPatched) return;
    window._androidWebAuthnPatched = true;

    var _callbacks = {};
    var _nextId = 0;

    function base64urlToArrayBuffer(b64) {
        var base64 = b64.replace(/-/g, '+').replace(/_/g, '/');
        while (base64.length % 4) base64 += '=';
        var binary = atob(base64);
        var buf = new ArrayBuffer(binary.length);
        var view = new Uint8Array(buf);
        for (var i = 0; i < binary.length; i++) view[i] = binary.charCodeAt(i);
        return buf;
    }

    function serialize(obj) {
        return JSON.stringify(obj, function(key, value) {
            if (value instanceof ArrayBuffer)
                return {_type:'ArrayBuffer', data: Array.from(new Uint8Array(value))};
            if (ArrayBuffer.isView(value))
                return {_type:'ArrayBuffer', data: Array.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength))};
            return value;
        });
    }

    window._webauthnSuccess = function(callbackId, responseBase64) {
        var cb = _callbacks[callbackId];
        if (!cb) return;
        delete _callbacks[callbackId];
        try {
            var r = JSON.parse(atob(responseBase64));
            var credential = {
                id: r.id,
                type: r.type || 'public-key',
                rawId: base64urlToArrayBuffer(r.rawId),
                response: {},
                getClientExtensionResults: function() { return {}; }
            };
            var resp = r.response;
            if (resp.clientDataJSON)    credential.response.clientDataJSON    = base64urlToArrayBuffer(resp.clientDataJSON);
            if (resp.authenticatorData) credential.response.authenticatorData = base64urlToArrayBuffer(resp.authenticatorData);
            if (resp.signature)         credential.response.signature         = base64urlToArrayBuffer(resp.signature);
            if (resp.userHandle)        credential.response.userHandle        = base64urlToArrayBuffer(resp.userHandle);
            if (resp.attestationObject) credential.response.attestationObject = base64urlToArrayBuffer(resp.attestationObject);
            cb.resolve(credential);
        } catch(e) { cb.reject(e); }
    };

    window._webauthnError = function(callbackId, message) {
        var cb = _callbacks[callbackId];
        if (!cb) return;
        delete _callbacks[callbackId];
        cb.reject(new DOMException(message, 'NotAllowedError'));
    };

    var _credentialsImpl = {
        get: function(options) {
            return new Promise(function(resolve, reject) {
                var id = _nextId++;
                _callbacks[id] = {resolve: resolve, reject: reject};
                Android.webauthnGet(id, serialize(options));
            });
        },
        create: function(options) {
            return new Promise(function(resolve, reject) {
                var id = _nextId++;
                _callbacks[id] = {resolve: resolve, reject: reject};
                Android.webauthnCreate(id, serialize(options));
            });
        }
    };

    // navigator.credentials is a non-writable IDL attribute — override on the prototype.
    try {
        Object.defineProperty(Navigator.prototype, 'credentials', {
            get: function() { return _credentialsImpl; },
            configurable: true
        });
    } catch(e1) {
        try {
            Object.defineProperty(navigator, 'credentials', {
                value: _credentialsImpl, writable: true, configurable: true
            });
        } catch(e2) {
            navigator.credentials = _credentialsImpl;
        }
    }
})();
