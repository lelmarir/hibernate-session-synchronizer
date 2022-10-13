package com.example;

import static javax.persistence.GenerationType.IDENTITY;

import java.util.HashSet;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.OneToMany;

@Entity
public class Foo {

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Integer id;

	@OneToMany(cascade = CascadeType.ALL, mappedBy = "foo", fetch = FetchType.LAZY)
	private Set<Bar> bars = new HashSet<>();

	public Foo() {
		// noop
	}

	public Set<Bar> getBars() {
		return bars;
	}

	public void setBars(Set<Bar> bars) {
		this.bars = bars;
	}

	@Override
	public String toString() {
		return "Foo [id=" + id + ", bars=" + bars + "]";
	}

}
