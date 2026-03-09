FROM node:20

RUN apt-get update && apt-get install -y --no-install-recommends \
  less \
  git \
  procps \
  sudo \
  fzf \
  zsh \
  man-db \
  zip \
  gnupg2 \
  gh \
  iptables \
  ipset \
  iproute2 \
  dnsutils \
  aggregate \
  jq \
  nano \
  vim \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV DEVCONTAINER=true

USER node
WORKDIR /home/node

RUN curl -fsSL "https://get.sdkman.io" | bash

RUN bash -c "source /home/node/.sdkman/bin/sdkman-init.sh && \
  sdk install java $(sdk list java | grep -oP '25(\.[0-9]+)*-tem' | head -n 1) && \
  sdk install sbt"

RUN curl -fsSL https://claude.ai/install.sh | bash

RUN echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc

WORKDIR /project
ENTRYPOINT ["bash"]
