import { config } from '@vue/test-utils'

config.global.stubs = {
  RouterLink: {
    props: ['to'],
    template: '<a><slot /></a>',
  },
  RouterView: {
    template: '<main data-testid="router-view" />',
  },
}
