import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from '@/App.vue'

describe('App', () => {
  it('renders the router outlet shell', () => {
    const wrapper = mount(App)

    expect(wrapper.find('[data-testid="router-view"]').exists()).toBe(true)
  })
})
