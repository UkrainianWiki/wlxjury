import '@testing-library/jest-dom'

// MUI DataGrid uses ResizeObserver — polyfill for jsdom
class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserver
