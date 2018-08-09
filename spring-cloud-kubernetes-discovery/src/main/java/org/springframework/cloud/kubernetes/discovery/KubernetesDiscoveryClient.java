/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.kubernetes.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.util.Assert;

public class KubernetesDiscoveryClient implements DiscoveryClient {

	private static final Log log = LogFactory.getLog(KubernetesDiscoveryClient.class);
	private static final String HOSTNAME = "HOSTNAME";

	private KubernetesClient kubernetesClient;
	private KubernetesDiscoveryProperties kubernetesDiscoveryProperties;

	public KubernetesDiscoveryClient(KubernetesClient client, KubernetesDiscoveryProperties kubernetesDiscoveryProperties) {
		this.kubernetesClient = client;
		this.kubernetesDiscoveryProperties = kubernetesDiscoveryProperties;
	}

	public KubernetesClient getClient() {
		return kubernetesClient;
	}

	public void setClient(KubernetesClient client) {
		this.kubernetesClient = client;
	}

	@Override
	public String description() {
		return "Kubernetes Discovery Client";
	}

	public ServiceInstance getLocalServiceInstance() {
		String serviceName = kubernetesDiscoveryProperties.getServiceName();
		String podName = System.getenv(HOSTNAME);
		ServiceInstance serviceInstance = new DefaultServiceInstance(serviceName,
			"localhost",
			8080,
			false);

		Endpoints endpoints = kubernetesClient.endpoints().withName(serviceName).get();
		if (Utils.isNullOrEmpty(podName) || endpoints == null) {
			return serviceInstance;
		}

		Optional<Service> service = Optional.ofNullable(kubernetesClient.services().withName(serviceName).get());
		Map<String, String> labels = service.isPresent() ? service.get().getMetadata().getLabels() : null;

		List<EndpointSubset> subsets = endpoints.getSubsets();
		if (subsets != null) {
			for (EndpointSubset s : subsets) {
				Optional<EndpointPort> optionalEndpointPort = s.getPorts().stream().findFirst();
				if (optionalEndpointPort.isPresent()) {
					List<EndpointAddress> addresses = s.getAddresses();
					for (EndpointAddress a : addresses) {
						serviceInstance = new KubernetesServiceInstance(serviceName,
							a,
							optionalEndpointPort.get(),
							labels,
							false);
						break;
					}
				}
			}
		}
		return serviceInstance;
	}

	@Override
	public List<ServiceInstance> getInstances(String serviceId) {
		Assert.notNull(serviceId,
			"[Assertion failed] - the object argument must be null");
		return getServiceInstances(serviceId);
	}

	private List<ServiceInstance> getServiceInstances(String serviceId) {
		List<ServiceInstance> instances = new ArrayList<>();
		try {
			Optional<Service> service = Optional.ofNullable(kubernetesClient.services().withName(serviceId).get());
			Map<String, String> labels = service.isPresent() ? service.get().getMetadata().getLabels() : null;

			Endpoints value = kubernetesClient.endpoints().withName(serviceId).get();
			Optional<Endpoints> endpoints = Optional.ofNullable(value);
			log.debug("value = " + value);

			if (endpoints.isPresent()) {
				List<EndpointSubset> endpointSubsets = endpoints.get().getSubsets();
				for (EndpointSubset endpointSubset : endpointSubsets) {
					Optional<EndpointPort> optionalEndpointPort = endpointSubset.getPorts().stream()
						.findFirst();
					if (optionalEndpointPort.isPresent()) {
						instances.addAll(endpointSubset.getAddresses().stream()
							.map(endpointAddress -> {
								KubernetesServiceInstance kubernetesServiceInstance = new KubernetesServiceInstance(serviceId,
									endpointAddress,
									optionalEndpointPort.get(),
									labels,
									false);
								return kubernetesServiceInstance;
							})
							.collect(Collectors.toList()));
					}
				}
			}
		} catch (Exception e) {
			log.error("Error calling Kubernetes server", e);
		}
		return instances;
	}

	@Override
	public List<String> getServices() {
		return kubernetesClient.services().list().getItems().stream()
			.map(s -> s.getMetadata().getName())
			.collect(Collectors.toList());
	}
}
