import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import '@vaadin/button';
import '@vaadin/horizontal-layout';
import '@vaadin/vertical-layout';
import '@vaadin/text-field';
import '@vaadin/text-area';
import '@vaadin/select';
import '@vaadin/checkbox';
import '@vaadin/notification';

export class QwcSsfStreamManagement extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host {
            display: block;
            padding: 1rem 1.25rem;
            font-family: var(--lumo-font-family);
            color: var(--lumo-body-text-color);
        }
        h3 {
            margin: 0 0 0.75rem 0;
        }
        .field-label {
            color: var(--lumo-secondary-text-color);
            font-size: var(--lumo-font-size-s);
        }
        .status-badge {
            display: inline-block;
            padding: 0.15rem 0.6rem;
            border-radius: var(--lumo-border-radius-m);
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.04em;
            font-size: var(--lumo-font-size-s);
        }
        .status-enabled  { background: var(--lumo-success-color-10pct); color: var(--lumo-success-text-color); }
        .status-paused   { background: var(--lumo-warning-color-10pct); color: var(--lumo-warning-text-color); }
        .status-disabled { background: var(--lumo-error-color-10pct);   color: var(--lumo-error-text-color); }
        .status-unknown  { background: var(--lumo-contrast-10pct);      color: var(--lumo-secondary-text-color); }
        .row {
            display: flex;
            gap: 1.25rem;
            flex-wrap: wrap;
            margin-bottom: 0.6rem;
        }
        .field {
            min-width: 0;
            flex: 1 1 18rem;
        }
        .value {
            word-break: break-all;
        }
        .actions {
            display: flex;
            gap: 0.5rem;
            margin-top: 1rem;
            flex-wrap: wrap;
        }
        .error {
            color: var(--lumo-error-text-color);
            background: var(--lumo-error-color-10pct);
            padding: 0.6rem 0.8rem;
            border-radius: var(--lumo-border-radius-m);
            margin-top: 0.75rem;
            white-space: pre-wrap;
        }
    `;

    static properties = {
        _stream:        { state: true },
        _config:        { state: true },
        _status:        { state: true },
        _reason:        { state: true },
        _verify:        { state: true },
        _subjFormat:    { state: true },
        _subjValue:     { state: true },
        _subjIss:       { state: true },
        _subjVerified:  { state: true },
        _subjResult:    { state: true },
        _registration:  { state: true },
        _aliases:       { state: true },
        _error:         { state: true },
        _busy:          { state: true },
    };

    constructor() {
        super();
        this._stream = null;
        this._config = null;
        this._status = null;
        this._reason = '';
        this._verify = null;
        this._subjFormat = 'email';
        this._subjValue = '';
        this._subjIss = '';
        this._subjVerified = true;
        this._subjResult = null;
        this._registration = null;
        this._aliases = null;
        this._error = null;
        this._busy = false;
    }

    connectedCallback() {
        super.connectedCallback();
        this._checkRegistrationThenLoad();
    }

    _checkRegistrationThenLoad() {
        this.jsonRpc.registrationStatus().then(r => {
            this._registration = r.result;
            if (this._registration && this._registration.ready) {
                this._loadStream();
                this._loadConfiguration();
                this._loadStatus();
                this._loadAliases();
            }
        }).catch(e => this._setError(e));
    }

    _loadAliases() {
        this.jsonRpc.configuredAliases().then(r => {
            this._aliases = r.result;
        }).catch(e => this._setError(e));
    }

    _loadStream() {
        this.jsonRpc.configuredStream().then(r => {
            this._stream = r.result;
        }).catch(e => this._setError(e));
    }

    _loadConfiguration() {
        this.jsonRpc.streamConfiguration().then(r => {
            this._config = r.result;
            this._error = null;
        }).catch(e => this._setError(e));
    }

    _loadStatus() {
        this._busy = true;
        this.jsonRpc.status().then(r => {
            this._status = r.result;
            this._error = null;
            this._busy = false;
        }).catch(e => { this._setError(e); this._busy = false; });
    }

    _update(target) {
        this._busy = true;
        this.jsonRpc.updateStatus({ status: target, reason: this._reason || '' }).then(r => {
            this._status = r.result;
            this._error = null;
            this._busy = false;
            this._loadConfiguration();
        }).catch(e => { this._setError(e); this._busy = false; });
    }

    _refreshAll() {
        this._loadConfiguration();
        this._loadStatus();
    }

    _verifyStream() {
        this._busy = true;
        this.jsonRpc.requestVerification().then(r => {
            this._verify = r.result;
            this._error = null;
            this._busy = false;
        }).catch(e => { this._setError(e); this._busy = false; });
    }

    _subjectAddOrRemove(op) {
        this._busy = true;
        this._subjResult = null;
        const params = {
            format: this._subjFormat,
            value: this._subjValue,
            issForIssSub: this._subjIss,
        };
        const call = (op === 'add')
            ? this.jsonRpc.addSubject({ ...params, verified: this._subjVerified })
            : this.jsonRpc.removeSubject(params);
        call.then(r => {
            this._subjResult = r.result;
            this._error = null;
            this._busy = false;
        }).catch(e => { this._setError(e); this._busy = false; });
    }

    _setError(e) {
        const msg = (e && e.error && e.error.message) ? e.error.message
                  : (e && e.message) ? e.message
                  : JSON.stringify(e);
        this._error = msg;
    }

    _renderStatusBadge(status) {
        if (!status) return html`<span class="status-badge status-unknown">unknown</span>`;
        const lower = String(status).toLowerCase();
        const known = ['enabled', 'paused', 'disabled'].includes(lower) ? lower : 'unknown';
        return html`<span class="status-badge status-${known}">${lower}</span>`;
    }

    _renderStream() {
        if (!this._stream) return html``;
        const s = this._stream;
        return html`
            <div class="row">
                <div class="field">
                    <div class="field-label">Stream id</div>
                    <div class="value">${s.streamId ?? '—'}</div>
                </div>
                <div class="field">
                    <div class="field-label">Transmitter issuer</div>
                    <div class="value">${s.transmitterIssuer ?? '—'}</div>
                </div>
            </div>
            <div class="row">
                <div class="field">
                    <div class="field-label">Stream management</div>
                    <div class="value">${s.streamManagement}</div>
                </div>
                <div class="field">
                    <div class="field-label">Delivery method</div>
                    <div class="value">${s.deliveryMethod}</div>
                </div>
                <div class="field">
                    <div class="field-label">Expected audience</div>
                    <div class="value">${s.expectedAudience ?? '—'}</div>
                </div>
            </div>
        `;
    }

    _renderList(items) {
        if (!items || items.length === 0) return html`<div class="value">—</div>`;
        return html`<ul style="margin:0; padding-left: 1.1rem;">
            ${items.map(i => html`<li class="value">${i}</li>`)}
        </ul>`;
    }

    /**
     * Renders an event-type URI list with each URI's configured alias inline,
     * e.g. `CaepSessionRevoked  https://schemas.openid.net/secevent/caep/event-type/session-revoked`.
     * Falls back to the bare URI when no alias is registered for it.
     */
    _renderEventList(items) {
        if (!items || items.length === 0) return html`<div class="value">—</div>`;
        const eventAliases = (this._aliases && this._aliases.eventTypeAliases) || {};
        return html`<ul style="margin:0; padding-left: 1.1rem;">
            ${items.map(uri => {
                const alias = eventAliases[uri];
                return html`
                    <li class="value" style="margin-bottom:0.2rem">
                        ${alias ? html`<strong>${alias}</strong>
                            <div style="font-size:var(--lumo-font-size-xs);color:var(--lumo-secondary-text-color);word-break:break-all">${uri}</div>`
                                : html`<span>${uri}</span>`}
                    </li>
                `;
            })}
        </ul>`;
    }

    _issuerWithAlias(issuer) {
        if (!issuer) return '—';
        const alias = (this._aliases && this._aliases.issuerAliases) ? this._aliases.issuerAliases[issuer] : null;
        return alias
            ? html`<strong>${alias}</strong>
                <div style="font-size:var(--lumo-font-size-xs);color:var(--lumo-secondary-text-color);word-break:break-all">${issuer}</div>`
            : html`<span style="word-break:break-all">${issuer}</span>`;
    }

    _renderConfiguration() {
        if (!this._config) return html`<div>Loading current configuration…</div>`;
        const c = this._config;
        return html`
            ${this._aliases && this._aliases.receiverAlias ? html`
                <div class="row">
                    <div class="field">
                        <div class="field-label">receiver alias (ssf.receiver.alias)</div>
                        <div class="value"><code>${this._aliases.receiverAlias}</code></div>
                    </div>
                </div>
            ` : ''}
            ${c.description ? html`
                <div class="row">
                    <div class="field">
                        <div class="field-label">description</div>
                        <div class="value">${c.description}</div>
                    </div>
                </div>
            ` : ''}
            <div class="row">
                <div class="field">
                    <div class="field-label">stream_id</div>
                    <div class="value">${c.streamId ?? '—'}</div>
                </div>
                <div class="field">
                    <div class="field-label">iss</div>
                    <div class="value">${this._issuerWithAlias(c.iss)}</div>
                </div>
            </div>
            <div class="row">
                <div class="field">
                    <div class="field-label">aud</div>
                    ${this._renderList(c.aud)}
                </div>
                <div class="field">
                    <div class="field-label">min_verification_interval</div>
                    <div class="value">${c.minVerificationInterval ?? '—'}</div>
                </div>
                <div class="field">
                    <div class="field-label">inactivity_timeout</div>
                    <div class="value">${c.inactivityTimeout ?? '—'}</div>
                </div>
            </div>
            <div class="row">
                <div class="field">
                    <div class="field-label">delivery</div>
                    <div class="value">
                        ${c.delivery
                            ? html`${c.delivery.method ?? '—'}${c.delivery.endpointUrl
                                ? html` → ${c.delivery.endpointUrl}` : ''}`
                            : '—'}
                    </div>
                </div>
            </div>
            <div class="row">
                <div class="field">
                    <div class="field-label">events_supported</div>
                    ${this._renderEventList(c.eventsSupported)}
                </div>
                <div class="field">
                    <div class="field-label">events_requested</div>
                    ${this._renderEventList(c.eventsRequested)}
                </div>
                <div class="field">
                    <div class="field-label">events_delivered</div>
                    ${this._renderEventList(c.eventsDelivered)}
                </div>
            </div>
            ${c.description ? html`
                <div class="row">
                    <div class="field">
                        <div class="field-label">description</div>
                        <div class="value">${c.description}</div>
                    </div>
                </div>
            ` : ''}
        `;
    }

    _renderSubjects() {
        const formats = [
            { label: 'email',   value: 'email' },
            { label: 'opaque',  value: 'opaque' },
            { label: 'iss_sub', value: 'iss_sub' },
            { label: 'complex', value: 'complex' },
        ];
        const valueLabel = ({
            email:   'email',
            opaque:  'id',
            iss_sub: 'sub',
            complex: 'JSON object (e.g. { "user": { "format":"email", "email":"a@b" } })',
        })[this._subjFormat] || 'value';
        const isComplex = this._subjFormat === 'complex';

        return html`
            <div class="row">
                <div class="field" style="flex: 0 1 12rem;">
                    <vaadin-select label="Format"
                        .items=${formats}
                        .value=${this._subjFormat}
                        @value-changed=${e => this._subjFormat = e.detail.value}>
                    </vaadin-select>
                </div>
                ${this._subjFormat === 'iss_sub' ? html`
                    <div class="field">
                        <vaadin-text-field label="iss" style="width:100%"
                            .value=${this._subjIss}
                            @value-changed=${e => this._subjIss = e.detail.value}>
                        </vaadin-text-field>
                    </div>
                ` : ''}
            </div>
            <div class="row">
                <div class="field">
                    ${isComplex
                        ? html`<vaadin-text-area label=${valueLabel} style="width:100%; min-height: 8rem;"
                                .value=${this._subjValue}
                                @value-changed=${e => this._subjValue = e.detail.value}>
                              </vaadin-text-area>`
                        : html`<vaadin-text-field label=${valueLabel} style="width:100%"
                                .value=${this._subjValue}
                                @value-changed=${e => this._subjValue = e.detail.value}>
                              </vaadin-text-field>`}
                </div>
            </div>
            <div class="row">
                <vaadin-checkbox label="verified"
                    .checked=${this._subjVerified}
                    @checked-changed=${e => this._subjVerified = e.detail.value}>
                </vaadin-checkbox>
            </div>
            <div class="actions">
                <vaadin-button theme="primary success" ?disabled=${this._busy}
                    @click=${() => this._subjectAddOrRemove('add')}>Add subject</vaadin-button>
                <vaadin-button theme="primary error" ?disabled=${this._busy}
                    @click=${() => this._subjectAddOrRemove('remove')}>Remove subject</vaadin-button>
            </div>
            ${this._subjResult ? html`
                <div class="row" style="margin-top:0.6rem">
                    <div class="field">
                        <div class="field-label">Last operation</div>
                        <div class="value">${this._subjResult.operation}</div>
                    </div>
                    <div class="field">
                        <div class="field-label">Subject</div>
                        <div class="value"><code>${JSON.stringify(this._subjResult.subject)}</code></div>
                    </div>
                </div>
            ` : ''}
        `;
    }

    _renderStatus() {
        if (!this._status) return html`<div>Loading current status…</div>`;
        return html`
            <div class="row">
                <div class="field">
                    <div class="field-label">Current status</div>
                    <div>${this._renderStatusBadge(this._status.status)}</div>
                </div>
                ${this._status.reason ? html`
                    <div class="field">
                        <div class="field-label">Reason</div>
                        <div class="value">${this._status.reason}</div>
                    </div>
                ` : ''}
            </div>
        `;
    }


    _renderRegistrationPending() {
        const r = this._registration;
        return html`
            <h3>Configured stream</h3>
            <div class="row">
                <div class="field">
                    <div class="field-label">Stream management mode</div>
                    <div class="value">${r ? r.mode : '—'}</div>
                </div>
            </div>
            <div style="
                padding: 0.75rem 1rem;
                background: var(--lumo-contrast-5pct);
                border-left: 4px solid var(--lumo-primary-color);
                border-radius: var(--lumo-border-radius-m);
                margin-top: 0.75rem;
            ">
                <div style="font-weight:600;margin-bottom:0.25rem">Stream registration in progress…</div>
                <div style="color:var(--lumo-secondary-text-color);font-size:var(--lumo-font-size-s)">
                    ${(r && r.message) || 'Waiting for the receiver-managed registrar to discover or create the stream on the transmitter.'}
                </div>
            </div>
            <div class="actions" style="margin-top:0.75rem">
                <vaadin-button theme="primary" @click=${() => this._checkRegistrationThenLoad()}>
                    Retry
                </vaadin-button>
            </div>
        `;
    }

    render() {
        if (this._registration && !this._registration.ready) {
            return this._renderRegistrationPending();
        }
        return html`
            <h3>Configured stream (local)</h3>
            ${this._renderStream()}

            <h3 style="margin-top:1.25rem">Stream configuration (from transmitter)</h3>
            ${this._renderConfiguration()}

            <h3 style="margin-top:1.25rem">Status</h3>
            ${this._renderStatus()}

            <h3 style="margin-top:1.25rem">Toggle</h3>
            <vaadin-text-field
                label="Reason (optional)"
                style="width:100%"
                .value=${this._reason}
                @value-changed=${e => this._reason = e.detail.value}>
            </vaadin-text-field>
            <div class="actions">
                <vaadin-button theme="primary success" ?disabled=${this._busy}
                    @click=${() => this._update('enabled')}>Enable</vaadin-button>
                <vaadin-button theme="primary contrast" ?disabled=${this._busy}
                    @click=${() => this._update('paused')}>Pause</vaadin-button>
                <vaadin-button theme="primary error" ?disabled=${this._busy}
                    @click=${() => this._update('disabled')}>Disable</vaadin-button>
                <vaadin-button theme="tertiary" ?disabled=${this._busy}
                    @click=${() => this._refreshAll()}>Refresh</vaadin-button>
            </div>

            <h3 style="margin-top:1.25rem">Subjects</h3>
            ${this._renderSubjects()}

            <h3 style="margin-top:1.25rem">Verify</h3>
            <div>
                Trigger a Verification Event with a freshly-generated <code>state</code>.
                The transmitter echoes it back in the resulting Verification SET that
                arrives at the push endpoint.
            </div>
            <div class="actions">
                <vaadin-button theme="primary" ?disabled=${this._busy}
                    @click=${() => this._verifyStream()}>Trigger verification</vaadin-button>
            </div>
            ${this._verify ? html`
                <div class="row" style="margin-top:0.6rem">
                    <div class="field">
                        <div class="field-label">Last requested state</div>
                        <div class="value"><code>${this._verify.state}</code></div>
                    </div>
                </div>
            ` : ''}

            ${this._error ? html`<div class="error">${this._error}</div>` : ''}
        `;
    }
}

customElements.define('qwc-ssf-stream-management', QwcSsfStreamManagement);
