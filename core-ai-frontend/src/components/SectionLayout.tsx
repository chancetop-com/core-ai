import type { ComponentType } from 'react';
import { NavLink, Outlet } from 'react-router-dom';

export interface SectionNavItem {
  label: string;
  to?: string;
  icon?: ComponentType<{ size: number; className?: string }>;
  show: boolean;
  end?: boolean;
  children?: SectionNavItem[];
}

interface SectionLayoutProps {
  title: string;
  subtitle?: string;
  items: SectionNavItem[];
}

function visibleItems(items: SectionNavItem[]): SectionNavItem[] {
  return items
    .filter(item => item.show)
    .map(item => ({ ...item, children: item.children ? visibleItems(item.children) : undefined }))
    .filter(item => item.to || (item.children && item.children.length > 0));
}

export default function SectionLayout({ title, subtitle, items }: SectionLayoutProps) {
  const navItems = visibleItems(items);

  return (
    <div className="flex h-full">
      <div className="w-56 shrink-0 border-r flex flex-col" style={{ borderColor: 'var(--color-border)' }}>
        <div className="p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold text-lg">{title}</h2>
          {subtitle && <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{subtitle}</div>}
        </div>
        <nav className="flex-1 p-2 flex flex-col gap-1 overflow-y-auto">
          {navItems.map(item => {
            const Icon = item.icon;
            const children = (item.children ?? []).filter((child): child is SectionNavItem & { to: string } => Boolean(child.to));

            return (
              <div key={item.to ?? item.label}>
                {item.to ? (
                  <NavLink to={item.to} end={item.end}
                    className="flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors"
                    style={({ isActive }) => ({
                      background: isActive ? 'var(--color-primary-bg)' : 'transparent',
                      color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                      fontWeight: isActive ? 500 : 400,
                    })}>
                    {Icon && <Icon size={16} />}
                    {item.label}
                  </NavLink>
                ) : (
                  <div className="flex items-center gap-3 px-3 pt-3 pb-1 text-xs font-medium"
                    style={{ color: 'var(--color-text-secondary)' }}>
                    {Icon && <Icon size={14} className="opacity-70" />}
                    {item.label}
                  </div>
                )}
                {children.length > 0 && (
                  <div className="ml-4 flex flex-col gap-0.5">
                    {children.map(child => {
                      const ChildIcon = child.icon;
                      return (
                        <NavLink key={child.to} to={child.to} end={child.end}
                          className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm transition-colors"
                          style={({ isActive }) => ({
                            background: isActive ? 'var(--color-primary-bg)' : 'transparent',
                            color: isActive ? 'var(--color-primary)' : 'var(--color-text-secondary)',
                            fontWeight: isActive ? 500 : 400,
                          })}>
                          {ChildIcon && <ChildIcon size={14} className="opacity-70" />}
                          {child.label}
                        </NavLink>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </nav>
      </div>
      <div className="flex-1 min-w-0 overflow-auto">
        <Outlet />
      </div>
    </div>
  );
}
