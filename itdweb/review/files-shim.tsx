// Minimal local stand-ins for the @instinct/files runtime, so the same
// canvas source builds as a plain static page for GitHub Pages.
// Differences from the hosted runtime:
//  - useLocalState persists to this origin's localStorage (browser-local,
//    never published), and reports the same ready/message shape.
//  - Layout primitives render plain semantic HTML with the same class names.
import React, { useCallback, useEffect, useState } from 'react';

export function FileCard({ children }: { children?: React.ReactNode }) {
    return <main className="fs-filecard">{children}</main>;
}
export function Header({ title, fact, intro }: { title: string; fact?: string; intro?: string }) {
    return <header className="fs-header">
        <h1>{title}</h1>
        {fact && <p className="fs-fact">{fact}</p>}
        {intro && <p className="fs-intro">{intro}</p>}
    </header>;
}
export function Paragraph({ children }: { children?: React.ReactNode }) { return <p>{children}</p>; }
export function Text({ children }: { children?: React.ReactNode }) { return <div className="fs-text">{children}</div>; }
export function Group({ label, heading, children }: { label: string; heading?: boolean | string; children?: React.ReactNode }) {
    return <section className="fs-group">
        <p className="fs-grouplabel">{label}</p>
        {typeof heading === 'string' ? <h2>{heading}</h2> : null}
        {children}
    </section>;
}
export function Caption({ children }: { children?: React.ReactNode }) { return <p className="fs-caption">{children}</p>; }
export function Closing({ children }: { children?: React.ReactNode }) { return <footer className="fs-closing"><p>{children}</p></footer>; }
export function TextLink({ href, children }: { href: string; children?: React.ReactNode }) {
    return <a href={href} target="_blank" rel="noreferrer noopener">{children}</a>;
}
export function Photos({ label, title, items }: { label: string; title?: string; items: { src: string; alt: string }[] }) {
    return <figure className="fs-photos" aria-label={label} title={title}>
        {items.map((it) => <img key={it.src} src={it.src} alt={it.alt} />)}
    </figure>;
}

export type LocalMeta = { ready: boolean; message: string };

// Same contract as the hosted hook: [value, setValue, meta]. Persisted per
// key in this browser's localStorage; nothing leaves the device.
export function useLocalState<T>(key: string, initial: T): [T, (v: T) => void, LocalMeta] {
    const storageKey = `intodo-review.${key}`;
    const [value, setValue] = useState<T>(() => {
        try {
            const raw = window.localStorage.getItem(storageKey);
            return raw != null ? (JSON.parse(raw) as T) : initial;
        } catch {
            return initial;
        }
    });
    const [meta, setMeta] = useState<LocalMeta>({ ready: false, message: 'Loading saved state…' });
    useEffect(() => {
        try {
            window.localStorage.setItem(storageKey, JSON.stringify(value));
            setMeta({ ready: true, message: 'Saved on this device, in this browser.' });
        } catch {
            setMeta({ ready: true, message: 'Not saved. Changes last only while this page is open.' });
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [value]);
    useEffect(() => {
        setMeta((m) => (m.ready ? m : { ready: true, message: 'Saved on this device, in this browser.' }));
    }, []);
    const set = useCallback((v: T) => setValue(v), []);
    return [value, set, meta];
}
