import { useState } from 'react';

export type Theme = 'light' | 'dark';
const KEY = 'kmarket-theme';


export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() =>
    document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light',
  );

  const toggle = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem(KEY, next);
    } catch {
      
    }
    setTheme(next);
  };

  return [theme, toggle];
}
