'use client'

import { useState } from 'react'

function getInitialState(): { dismissed: boolean; minimized: boolean } {
  if (typeof window === 'undefined') return { dismissed: false, minimized: false }
  const saved = localStorage.getItem('splash-state')
  if (saved === 'dismissed') return { dismissed: true, minimized: false }
  if (saved === 'minimized') return { dismissed: false, minimized: true }
  return { dismissed: false, minimized: false }
}

export default function Home() {
  const [dismissed, setDismissed] = useState(() => getInitialState().dismissed)
  const [minimized, setMinimized] = useState(() => getInitialState().minimized)

  const handleDismiss = () => {
    setDismissed(true)
    localStorage.setItem('splash-state', 'dismissed')
  }

  const handleMinimize = () => {
    setMinimized(true)
    localStorage.setItem('splash-state', 'minimized')
  }

  const handleRestore = () => {
    setMinimized(false)
    setDismissed(false)
    localStorage.removeItem('splash-state')
  }

  // Dismissed — clean empty state
  if (dismissed) {
    return (
      <div className="fixed bottom-4 right-4 z-50">
        <button
          onClick={handleRestore}
          className="px-3 py-1.5 text-xs text-muted-foreground bg-card border border-border rounded-md hover:bg-accent transition-colors cursor-pointer"
        >
          Restore
        </button>
      </div>
    )
  }

  // Minimized — small floating chip
  if (minimized) {
    return (
      <div className="fixed bottom-4 right-4 z-50 flex items-center gap-2">
        <button
          onClick={handleRestore}
          className="flex items-center gap-2 px-3 py-1.5 text-xs text-muted-foreground bg-card border border-border rounded-full hover:bg-accent transition-colors cursor-pointer"
        >
          <span className="text-sm">&#9788;</span>
          <span>Ready</span>
        </button>
      </div>
    )
  }

  // Full splash
  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-6">
      <div className="w-full max-w-md">
        {/* Header with actions */}
        <div className="flex justify-end gap-1 mb-4">
          <button
            onClick={handleMinimize}
            className="p-1.5 text-muted-foreground hover:text-foreground hover:bg-accent rounded-md transition-colors cursor-pointer"
            title="Minimize"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <line x1="2" y1="7" x2="12" y2="7" />
            </svg>
          </button>
          <button
            onClick={handleDismiss}
            className="p-1.5 text-muted-foreground hover:text-foreground hover:bg-accent rounded-md transition-colors cursor-pointer"
            title="Close"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <line x1="2" y1="2" x2="12" y2="12" />
              <line x1="12" y1="2" x2="2" y2="12" />
            </svg>
          </button>
        </div>

        {/* Card */}
        <div className="bg-card border border-border rounded-xl p-8 shadow-sm">
          <div className="flex flex-col items-center text-center gap-4">
            <div className="w-14 h-14 flex items-center justify-center">
              <img
                src="/logo.svg"
                alt="Z.ai"
                className="w-full h-full object-contain"
              />
            </div>

            <div className="space-y-1">
              <h1 className="text-lg font-medium text-foreground tracking-tight">
                Workspace
              </h1>
              <p className="text-sm text-muted-foreground">
                Send a message to start building.
              </p>
            </div>

            <div className="flex items-center gap-1.5 pt-1">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-xs text-muted-foreground">Dev server running</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
