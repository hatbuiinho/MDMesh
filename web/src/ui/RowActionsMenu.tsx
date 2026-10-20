import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

interface RowAction {
  label: string;
  onSelect: () => void;
  disabled?: boolean;
  danger?: boolean;
}

export function RowActionsMenu({ label, actions }: { label: string; actions: RowAction[] }) {
  const id = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ top: number; right: number } | null>(null);

  function close(restoreFocus = false) {
    setPosition(null);
    if (restoreFocus) trigger.current?.focus();
  }

  function open() {
    const rect = trigger.current?.getBoundingClientRect();
    if (!rect) return;
    const height = actions.length * 40 + 12;
    setPosition({
      top: Math.max(8, rect.bottom + height > window.innerHeight ? rect.top - height : rect.bottom + 4),
      right: Math.max(8, window.innerWidth - rect.right),
    });
  }

  useEffect(() => {
    if (!position) return;
    menu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    const onPointerDown = (event: PointerEvent) => {
      if (!menu.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) close();
    };
    const onViewportChange = () => close();
    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('resize', onViewportChange);
    window.addEventListener('scroll', onViewportChange, true);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('resize', onViewportChange);
      window.removeEventListener('scroll', onViewportChange, true);
    };
  }, [position]);

  return <>
    <button ref={trigger} className="btn btn-sm row-actions-trigger" aria-label={label}
      aria-haspopup="menu" aria-expanded={!!position} aria-controls={position ? id : undefined}
      onMouseDown={(event) => { if (position) event.preventDefault(); }}
      onClick={() => position ? close() : open()}
      onKeyDown={(event) => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); open(); }
      }}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <circle cx="12" cy="5" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="12" cy="19" r="2" />
      </svg>
    </button>
    {position && createPortal(<div ref={menu} id={id} className="row-actions-menu" role="menu" aria-label={label}
      style={position} onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) close();
      }} onKeyDown={(event) => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); }
        if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
          event.preventDefault();
          const items = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []);
          const index = items.indexOf(document.activeElement as HTMLButtonElement);
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 :
            (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length;
          items[next]?.focus();
        }
      }}>
      {actions.map((action) => <button key={action.label} type="button" role="menuitem"
        className={action.danger ? 'danger' : undefined} disabled={action.disabled}
        onClick={() => { close(true); action.onSelect(); }}>{action.label}</button>)}
    </div>, document.body)}
  </>;
}
