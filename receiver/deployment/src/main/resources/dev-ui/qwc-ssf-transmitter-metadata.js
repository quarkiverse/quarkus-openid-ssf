import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import '@vaadin/button';

/**
 * Read-only view of the SSF transmitter's `.well-known/ssf-configuration`
 * document — fetched (and cached) by SsfConfigurationResolver via a Quarkus
 * REST client. No outbound SSF management calls happen here, so this page
 * works regardless of whether the receiver-managed registrar has finished.
 */
export class QwcSsfTransmitterMetadata extends LitElement {

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
        ul.list {
            margin: 0;
            padding-left: 1.25rem;
        }
        .actions {
            display: flex;
            gap: 0.5rem;
            margin: 1rem 0 0.5rem 0;
        }
        .error {
            color: var(--lumo-error-text-color);
            background: var(--lumo-error-color-10pct);
            padding: 0.6rem 0.8rem;
            border-radius: var(--lumo-border-radius-m);
            margin-top: 0.75rem;
            white-space: pre-wrap;
        }
        pre.json {
            background: var(--lumo-contrast-5pct);
            padding: 0.75rem;
            border-radius: var(--lumo-border-radius-m);
            overflow-x: auto;
            font-size: var(--lumo-font-size-s);
        }
    `;

    static properties = {
        _metadata: { state: true },
        _error:    { state: true },
        _busy:     { state: true },
    };

    constructor() {
        super();
        this._metadata = null;
        this._error = null;
        this._busy = false;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    _load() {
        this._busy = true;
        this.jsonRpc.transmitterMetadata().then(r => {
            this._metadata = r.result;
            this._error = null;
            this._busy = false;
        }).catch(e => {
            this._setError(e);
            this._busy = false;
        });
    }

    _setError(e) {
        this._error = (e && e.error && e.error.message) ? e.error.message
                    : (e && e.message) ? e.message
                    : JSON.stringify(e);
    }

    _renderList(items) {
        if (!items || items.length === 0) return html`<span class="value">—</span>`;
        return html`<ul class="list">${items.map(i => html`<li class="value">${i}</li>`)}</ul>`;
    }

    _renderEndpoint(label, value) {
        return html`
            <div class="field">
                <div class="field-label">${label}</div>
                <div class="value">${value ?? '—'}</div>
            </div>
        `;
    }

    render() {
        const m = this._metadata;
        return html`
            <h3>Transmitter Metadata</h3>
            <div class="actions">
                <vaadin-button theme="tertiary small" @click=${() => this._load()} ?disabled=${this._busy}>
                    Refresh
                </vaadin-button>
            </div>
            ${this._error ? html`<div class="error">${this._error}</div>` : ''}
            ${m ? html`
                <div class="row">
                    ${this._renderEndpoint('Issuer', m.issuer)}
                    ${this._renderEndpoint('Spec version', m.specVersion)}
                </div>
                <div class="row">
                    ${this._renderEndpoint('Configuration endpoint', m.configurationEndpoint)}
                    ${this._renderEndpoint('JWKS URI', m.jwksUri)}
                </div>
                <div class="row">
                    ${this._renderEndpoint('Status endpoint', m.statusEndpoint)}
                    ${this._renderEndpoint('Verification endpoint', m.verificationEndpoint)}
                </div>
                <div class="row">
                    ${this._renderEndpoint('Add-subject endpoint', m.addSubjectEndpoint)}
                    ${this._renderEndpoint('Remove-subject endpoint', m.removeSubjectEndpoint)}
                </div>
                <div class="row">
                    <div class="field">
                        <div class="field-label">Delivery methods supported</div>
                        ${this._renderList(m.deliveryMethodsSupported)}
                    </div>
                    <div class="field">
                        <div class="field-label">Authorization schemes</div>
                        ${this._renderList(m.authorizationSchemes)}
                    </div>
                </div>
                <div class="row">
                    <div class="field">
                        <div class="field-label">Critical subject members</div>
                        ${this._renderList(m.criticalSubjectMembers)}
                    </div>
                </div>
                ${m.additionalProperties && Object.keys(m.additionalProperties).length > 0 ? html`
                    <div class="row">
                        <div class="field">
                            <div class="field-label">Additional properties (transmitter-specific)</div>
                            <pre class="json">${JSON.stringify(m.additionalProperties, null, 2)}</pre>
                        </div>
                    </div>
                ` : ''}
            ` : (this._busy ? html`<div>Loading…</div>` : '')}
        `;
    }
}

customElements.define('qwc-ssf-transmitter-metadata', QwcSsfTransmitterMetadata);
